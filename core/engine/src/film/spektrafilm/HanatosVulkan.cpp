#include "HanatosVulkan.h"
#include "ParallelPixels.h"
#include "log/Logger.h"

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <string_view>
#include <vector>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace filmkit::spektrafilm {
namespace {

using Clock = std::chrono::steady_clock;
using Pixel = std::array<float, 4>;
constexpr std::uint32_t kLanes = 64;
constexpr std::size_t kTile = 1536 * 1024;
constexpr std::uint32_t kShader[] = {
#include "HanatosSpirv.inc"
};
constexpr std::uint32_t kSharedShader[] = {
#include "HanatosSharedSpirv.inc"
};
constexpr VkFormat kSharedFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr auto kAhbHandle = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
constexpr VkImageSubresourceRange kColor{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

struct Projection {
    Pixel xyzFromRgb[3];
    float exposureGain;
    std::int32_t side;
    std::uint32_t count;
    std::uint32_t inputFormat;
    std::uint32_t width = 0;
    std::uint32_t firstRow = 0;
};
static_assert(sizeof(Pixel) == 16 && offsetof(Projection, width) == 64 && sizeof(Projection) == 72);

double elapsedMs(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

Pixel pack(const Triplet& value) {
    return {static_cast<float>(value[0]), static_cast<float>(value[1]),
            static_cast<float>(value[2]), 0.0f};
}

// Scoped to the current GLES context and one still. The interop copy never
// changes the original texture: it remains available for HDR and CPU fallback.
struct SharedGlImage {
    EGLDisplay display = eglGetCurrentDisplay();
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    AHardwareBuffer* buffer = nullptr;
    GLuint texture = 0, fbo = 0;

    ~SharedGlImage() {
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &texture);
        if (eglImage != EGL_NO_IMAGE_KHR) destroyImage(display, eglImage);
        if (buffer) AHardwareBuffer_release(buffer);
    }

    bool create(const HanatosSharedSource& dims) {
        const auto client = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        const auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        const auto bind = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        if (display == EGL_NO_DISPLAY || !client || !createImage || !destroyImage || !bind) return false;
        const AHardwareBuffer_Desc desc{.width = static_cast<std::uint32_t>(dims.width),
            .height = static_cast<std::uint32_t>(dims.height), .layers = 1,
            .format = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT,
            .usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            .stride = 0, .rfu0 = 0, .rfu1 = 0};
        if (AHardwareBuffer_allocate(&desc, &buffer) != 0) return false;
        const EGLint attrs[]{EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        eglImage = createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client(buffer), attrs);
        if (eglImage == EGL_NO_IMAGE_KHR) return false;
        GLint oldTexture = 0, oldDraw = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &oldTexture);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &oldDraw);
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        bind(GL_TEXTURE_2D, eglImage);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        const bool complete = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldDraw);
        glBindTexture(GL_TEXTURE_2D, oldTexture);
        return glGetError() == GL_NO_ERROR && complete;
    }

    bool copy(const HanatosSharedSource& dims) const {
        GLint oldDraw = 0;
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &oldDraw);
        const bool scissor = glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glBlitFramebuffer(0, 0, dims.width, dims.height, 0, 0, dims.width, dims.height,
                          GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, oldDraw);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        const GLsync done = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (!done) return false;
        const auto result = glClientWaitSync(done, GL_SYNC_FLUSH_COMMANDS_BIT, 5'000'000'000ull);
        glDeleteSync(done);
        return glGetError() == GL_NO_ERROR && (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED);
    }
};

class VulkanProjection {
public:
    ~VulkanProjection() {
        if (device) {
            if (pending) vkDeviceWaitIdle(device);
            freeBuffer(table);
            freeBuffer(image);
            freeBuffer(rawReadback);
            freeShared();
            vkDestroySampler(device, sampler, nullptr);
            vkDestroyQueryPool(device, queries, nullptr);
            vkDestroyFence(device, fence, nullptr);
            vkDestroyCommandPool(device, commandPool, nullptr);
            vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            vkDestroyPipeline(device, pipeline, nullptr);
            vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
            vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (instance) vkDestroyInstance(instance, nullptr);
    }

    bool initialize(bool shared = false);
    bool prepare(std::span<const Triplet> response, VkDeviceSize readbackBytes,
                 const SharedGlImage* shared = nullptr);
    bool dispatch(const Projection& params, double& gpuMs, bool release = false);
    void discardShared() { if (!pending) freeShared(); }
    Pixel* pixels() const { return image.mapped; }
    void* readbackTarget() const { return rawReadback.mapped; }
    std::uint32_t memoryFlags() const { return image.flags; }
    const char* name() const { return properties.deviceName; }

private:
    struct Buffer {
        VkBuffer handle = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        Pixel* mapped = nullptr;
        VkDeviceSize bytes = 0;
        VkMemoryPropertyFlags flags = 0;
    };

    bool findComputeQueue();
    bool allocateBuffer(Buffer& buffer, VkDeviceSize bytes);
    bool syncHost(const Buffer& buffer, bool read);
    bool importShared(AHardwareBuffer* buffer);
    void freeShared() {
        vkDestroyImageView(device, sharedView, nullptr);
        vkDestroyImage(device, sharedImage, nullptr);
        vkFreeMemory(device, sharedMemory, nullptr);
        sharedView = VK_NULL_HANDLE;
        sharedImage = VK_NULL_HANDLE;
        sharedMemory = VK_NULL_HANDLE;
    }
    void freeBuffer(Buffer& buffer) {
        if (buffer.mapped) vkUnmapMemory(device, buffer.memory);
        vkDestroyBuffer(device, buffer.handle, nullptr);
        vkFreeMemory(device, buffer.memory, nullptr);
        buffer = {};
    }

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    std::uint32_t family = 0;
    std::uint32_t timestampBits = 0;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool queries = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkImage sharedImage = VK_NULL_HANDLE;
    VkImageView sharedView = VK_NULL_HANDLE;
    VkDeviceMemory sharedMemory = VK_NULL_HANDLE;
    Buffer image, table, rawReadback;
    bool pending = false;
};

bool VulkanProjection::findComputeQueue() {
    std::uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS || !count) return false;
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) != VK_SUCCESS) return false;
    for (auto candidate : devices) {
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
            properties.limits.maxStorageBufferRange < kTile * sizeof(Pixel) ||
            properties.limits.maxComputeWorkGroupCount[0] < kTile / kLanes) continue;
        std::uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
        for (std::uint32_t i = 0; i < familyCount; ++i) {
            if (families[i].queueCount && (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                physical = candidate;
                family = i;
                timestampBits = families[i].timestampValidBits;
                return true;
            }
        }
    }
    return false;
}

bool VulkanProjection::initialize(bool shared) {
    const VkApplicationInfo app{.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "FilmKit", .apiVersion = shared ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0};
    const VkInstanceCreateInfo instanceInfo{.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                           .pApplicationInfo = &app};
    if (vkCreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS || !findComputeQueue()) return false;
    const char* extensions[]{VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                             VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME};
    if (shared && properties.apiVersion < VK_API_VERSION_1_1) return false;
    const float priority = 0.0f;
    const VkDeviceQueueCreateInfo queueInfo{.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
    const VkDeviceCreateInfo deviceInfo{.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1, .pQueueCreateInfos = &queueInfo,
        .enabledExtensionCount = shared ? 2u : 0u, .ppEnabledExtensionNames = shared ? extensions : nullptr};
    if (vkCreateDevice(physical, &deviceInfo, nullptr, &device) != VK_SUCCESS) return false;
    vkGetDeviceQueue(device, family, 0, &queue);

    const auto inputType = shared ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    const VkDescriptorSetLayoutBinding bindings[]{
        {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
        {2, inputType, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
    };
    const VkDescriptorSetLayoutCreateInfo setInfo{.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                                                .bindingCount = 3, .pBindings = bindings};
    if (vkCreateDescriptorSetLayout(device, &setInfo, nullptr, &setLayout) != VK_SUCCESS) return false;
    const VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Projection)};
    const VkPipelineLayoutCreateInfo layoutInfo{.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1, .pSetLayouts = &setLayout, .pushConstantRangeCount = 1, .pPushConstantRanges = &push};
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout) != VK_SUCCESS) return false;
    VkShaderModule shader = VK_NULL_HANDLE;
    const VkShaderModuleCreateInfo shaderInfo{.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = shared ? sizeof(kSharedShader) : sizeof(kShader), .pCode = shared ? kSharedShader : kShader};
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shader) != VK_SUCCESS) return false;
    const VkComputePipelineCreateInfo pipelineInfo{.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                  .stage = VK_SHADER_STAGE_COMPUTE_BIT, .module = shader, .pName = "main"},
        .layout = pipelineLayout};
    const auto result = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);
    vkDestroyShaderModule(device, shader, nullptr);
    if (result != VK_SUCCESS) return false;

    const VkDescriptorPoolSize poolSize[]{{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, shared ? 2u : 3u},
                                          {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}};
    const VkDescriptorPoolCreateInfo poolInfo{.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 1, .poolSizeCount = shared ? 2u : 1u, .pPoolSizes = poolSize};
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool) != VK_SUCCESS) return false;
    const VkDescriptorSetAllocateInfo setAlloc{.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = descriptorPool, .descriptorSetCount = 1, .pSetLayouts = &setLayout};
    if (vkAllocateDescriptorSets(device, &setAlloc, &descriptorSet) != VK_SUCCESS) return false;
    const VkCommandPoolCreateInfo commandPoolInfo{.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                                 .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,
                                                 .queueFamilyIndex = family};
    if (vkCreateCommandPool(device, &commandPoolInfo, nullptr, &commandPool) != VK_SUCCESS) return false;
    const VkCommandBufferAllocateInfo commandInfo{.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = commandPool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    if (vkAllocateCommandBuffers(device, &commandInfo, &command) != VK_SUCCESS) return false;
    const VkFenceCreateInfo fenceInfo{.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) return false;
    if (timestampBits) {
        const VkQueryPoolCreateInfo queryInfo{.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
                                             .queryType = VK_QUERY_TYPE_TIMESTAMP, .queryCount = 2};
        if (vkCreateQueryPool(device, &queryInfo, nullptr, &queries) != VK_SUCCESS) queries = VK_NULL_HANDLE;
    }
    if (shared) {
        const VkSamplerCreateInfo info{.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
            .magFilter = VK_FILTER_NEAREST, .minFilter = VK_FILTER_NEAREST,
            .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST, .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
        if (vkCreateSampler(device, &info, nullptr, &sampler) != VK_SUCCESS) return false;
    }
    return allocateBuffer(image, kTile * sizeof(Pixel));
}

bool VulkanProjection::importShared(AHardwareBuffer* buffer) {
    const auto getProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    VkAndroidHardwareBufferFormatPropertiesANDROID format{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, .pNext = &format};
    if (!getProperties || getProperties(device, buffer, &props) != VK_SUCCESS ||
        format.format != kSharedFormat || !(format.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ||
        !props.memoryTypeBits) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    const VkExternalMemoryImageCreateInfo external{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .handleTypes = kAhbHandle};
    const VkImageCreateInfo imageInfo{.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &external,
        .imageType = VK_IMAGE_TYPE_2D, .format = kSharedFormat, .extent = {desc.width, desc.height, 1},
        .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT};
    if (vkCreateImage(device, &imageInfo, nullptr, &sharedImage) != VK_SUCCESS) return false;
    const VkImportAndroidHardwareBufferInfoANDROID imported{
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, .buffer = buffer};
    const VkMemoryDedicatedAllocateInfo dedicated{.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &imported, .image = sharedImage};
    const VkMemoryAllocateInfo allocation{.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &dedicated,
        .allocationSize = props.allocationSize, .memoryTypeIndex = static_cast<std::uint32_t>(std::countr_zero(props.memoryTypeBits))};
    if (vkAllocateMemory(device, &allocation, nullptr, &sharedMemory) != VK_SUCCESS ||
        vkBindImageMemory(device, sharedImage, sharedMemory, 0) != VK_SUCCESS) return false;
    const VkImageViewCreateInfo viewInfo{.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = sharedImage, .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = kSharedFormat, .subresourceRange = kColor};
    return vkCreateImageView(device, &viewInfo, nullptr, &sharedView) == VK_SUCCESS;
}

bool VulkanProjection::allocateBuffer(Buffer& buffer, VkDeviceSize bytes) {
    const VkBufferCreateInfo info{.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = bytes,
                                  .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT};
    if (vkCreateBuffer(device, &info, nullptr, &buffer.handle) != VK_SUCCESS) return false;
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device, buffer.handle, &requirements);
    VkPhysicalDeviceMemoryProperties memory{};
    vkGetPhysicalDeviceMemoryProperties(physical, &memory);
    std::uint32_t index = memory.memoryTypeCount;
    int best = -1;
    for (std::uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
        const auto flags = memory.memoryTypes[i].propertyFlags;
        if (!(requirements.memoryTypeBits & (1u << i)) || !(flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) continue;
        const int score = 2 * bool(flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) +
                          bool(flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (score > best) {
            best = score;
            index = i;
        }
    }
    if (index == memory.memoryTypeCount) return false;
    const VkMemoryAllocateInfo allocation{.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size, .memoryTypeIndex = index};
    if (vkAllocateMemory(device, &allocation, nullptr, &buffer.memory) != VK_SUCCESS ||
        vkBindBufferMemory(device, buffer.handle, buffer.memory, 0) != VK_SUCCESS) return false;
    void* pointer = nullptr;
    if (vkMapMemory(device, buffer.memory, 0, VK_WHOLE_SIZE, 0, &pointer) != VK_SUCCESS) return false;
    buffer.mapped = static_cast<Pixel*>(pointer);
    buffer.bytes = bytes;
    buffer.flags = memory.memoryTypes[index].propertyFlags;
    return true;
}

bool VulkanProjection::syncHost(const Buffer& buffer, bool read) {
    if (buffer.flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) return true;
    const VkMappedMemoryRange range{.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
                                   .memory = buffer.memory, .size = VK_WHOLE_SIZE};
    const auto result = read ? vkInvalidateMappedMemoryRanges(device, 1, &range)
                             : vkFlushMappedMemoryRanges(device, 1, &range);
    return result == VK_SUCCESS;
}

bool VulkanProjection::prepare(std::span<const Triplet> response, VkDeviceSize readbackBytes,
                               const SharedGlImage* shared) {
    if (pending || response.size() > properties.limits.maxStorageBufferRange / sizeof(Pixel)) return false;
    const auto bytes = response.size() * sizeof(Pixel);
    if (table.bytes != bytes) {
        freeBuffer(table);
        if (!allocateBuffer(table, bytes)) return false;
    }
    for (std::size_t i = 0; i < response.size(); ++i) {
        table.mapped[i] = pack(response[i]);
        for (float v : table.mapped[i]) if (!std::isfinite(v)) return false;
    }
    if (!syncHost(table, false)) return false;
    if (shared && !importShared(shared->buffer)) return false;
    if (readbackBytes > rawReadback.bytes) {
        freeBuffer(rawReadback);
        if (!allocateBuffer(rawReadback, readbackBytes)) return false;
    }
    const VkDescriptorBufferInfo images{image.handle, 0, image.bytes};
    const VkDescriptorBufferInfo responses{table.handle, 0, table.bytes};
    const VkDescriptorBufferInfo readback = readbackBytes
        ? VkDescriptorBufferInfo{rawReadback.handle, 0, readbackBytes}
        : images;
    const VkDescriptorImageInfo sampled{sampler, sharedView, VK_IMAGE_LAYOUT_GENERAL};
    const VkWriteDescriptorSet writes[]{
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = descriptorSet, .dstBinding = 0,
         .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &images},
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = descriptorSet, .dstBinding = 1,
         .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &responses},
        {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = descriptorSet, .dstBinding = 2,
         .descriptorCount = 1, .descriptorType = shared ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
         .pImageInfo = shared ? &sampled : nullptr, .pBufferInfo = shared ? nullptr : &readback},
    };
    vkUpdateDescriptorSets(device, 3, writes, 0, nullptr);
    return true;
}

bool VulkanProjection::dispatch(const Projection& params, double& gpuMs, bool release) {
    if ((!sharedImage && !syncHost(params.inputFormat ? rawReadback : image, false)) ||
        vkResetFences(device, 1, &fence) != VK_SUCCESS ||
        vkResetCommandPool(device, commandPool, 0) != VK_SUCCESS) return false;
    const VkCommandBufferBeginInfo begin{.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    if (vkBeginCommandBuffer(command, &begin) != VK_SUCCESS) return false;
    if (sharedImage && params.firstRow == 0) {
        const VkImageMemoryBarrier acquire{.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .dstAccessMask = VK_ACCESS_SHADER_READ_BIT, .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
            .dstQueueFamilyIndex = family, .image = sharedImage, .subresourceRange = kColor};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &acquire);
    }
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                            0, 1, &descriptorSet, 0, nullptr);
    vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), &params);
    if (queries) {
        vkCmdResetQueryPool(command, queries, 0, 2);
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, 0);
    }
    vkCmdDispatch(command, (params.count + kLanes - 1) / kLanes, 1, 1);
    if (queries) vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, 1);
    if (sharedImage && release) {
        const VkImageMemoryBarrier barrier{.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = VK_ACCESS_SHADER_READ_BIT, .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
            .newLayout = VK_IMAGE_LAYOUT_GENERAL, .srcQueueFamilyIndex = family,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT, .image = sharedImage, .subresourceRange = kColor};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &barrier);
    }
    const VkMemoryBarrier toHost{.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 1, &toHost, 0, nullptr, 0, nullptr);
    if (vkEndCommandBuffer(command) != VK_SUCCESS) return false;
    const VkSubmitInfo submit{.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                              .commandBufferCount = 1, .pCommandBuffers = &command};
    if (vkQueueSubmit(queue, 1, &submit, fence) != VK_SUCCESS) return false;
    pending = true;
    if (vkWaitForFences(device, 1, &fence, VK_TRUE, 5'000'000'000ull) != VK_SUCCESS) return false;
    pending = false;
    std::uint64_t ticks[2];
    if (queries && vkGetQueryPoolResults(device, queries, 0, 2, sizeof(ticks), ticks, sizeof(ticks[0]),
                                        VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
        const auto mask = std::numeric_limits<std::uint64_t>::max() >> (64 - timestampBits);
        gpuMs += static_cast<double>((ticks[1] - ticks[0]) & mask) * properties.limits.timestampPeriod / 1e6;
    }
    return syncHost(image, true);
}

}  // namespace

bool supportsHanatosVulkan() noexcept {
    static const bool supported = []() noexcept {
        try {
            VulkanProjection context;
            // Exercise production-sized mapped input, descriptors and a real
            // dispatch. The settings thread releases probe allocations on exit.
            if (!context.initialize()) return false;
            constexpr std::array<Triplet, 4> response{{
                {1., 2., 3.}, {1., 2., 3.}, {1., 2., 3.}, {1., 2., 3.},
            }};
            if (!context.prepare(response, kTile * sizeof(Pixel))) return false;
            *static_cast<Pixel*>(context.readbackTarget()) = {0.25f, 0.5f, 0.25f, 1.0f};
            Projection p{{{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}}, 1.0f, 2, 1, 2};
            double gpuMs = 0.0;
            if (!context.dispatch(p, gpuMs)) return false;
            const auto& out = *context.pixels();
            return out[3] == 0.0f && std::abs(out[0] - 1.0f) < 1e-5f &&
                   std::abs(out[1] - 2.0f) < 1e-5f && std::abs(out[2] - 3.0f) < 1e-5f;
        } catch (...) {
            return false;
        }
    }();
    return supported;
}

HanatosVulkanMode hanatosVulkanMode() {
    const char* mode = std::getenv("FILMKIT_VULKAN_HANATOS");
    if (!mode) return HanatosVulkanMode::Off;
    if (std::string_view(mode) == "1") return HanatosVulkanMode::CpuInput;
    if (std::string_view(mode) == "2") return HanatosVulkanMode::RawReadback;
#if defined(FILMKIT_VULKAN_AHB_PROBE)
    if (std::string_view(mode) == "3") return HanatosVulkanMode::SharedImage;
    if (std::string_view(mode) == "4") return HanatosVulkanMode::SharedImageSimd;
#endif
    return HanatosVulkanMode::Off;
}

std::optional<std::size_t> projectHanatosVulkan(
    std::span<Triplet> pixels, const std::array<Triplet, 3>& xyzFromRgb,
    std::span<const Triplet> response, std::size_t side, double exposureGain,
    std::size_t workers, const HanatosReadback* readback, const HanatosSharedSource* shared) {
    if (side < 2 || side > response.size() / side || side * side != response.size() ||
        response.size() > std::numeric_limits<std::int32_t>::max()) return 0;
    Projection params{{pack(xyzFromRgb[0]), pack(xyzFromRgb[1]), pack(xyzFromRgb[2])},
                      static_cast<float>(exposureGain), static_cast<std::int32_t>(side), 0, 0};
    if (!std::isfinite(params.exposureGain) || (params.exposureGain == 0.0f && exposureGain != 0.0)) return 0;
    for (const auto& row : params.xyzFromRgb) for (float v : row) if (!std::isfinite(v)) return 0;
    std::size_t tile = kTile;
    VkDeviceSize readbackBytes = 0;
    if (readback) {
        if (!readback->width || readback->width > kTile || pixels.size() % readback->width) return 0;
        tile = std::min(kTile / readback->width, std::size_t{512}) * readback->width;
        params.inputFormat = readback->halfFloat ? 1 : 2;
        readbackBytes = tile * (readback->halfFloat ? 8 : 16);
    }
    if (shared) {
        if (readback || shared->width <= 0 || shared->height <= 0 ||
            static_cast<std::size_t>(shared->width) > kTile ||
            pixels.size() != static_cast<std::size_t>(shared->width) * shared->height) return 0;
        params.width = static_cast<std::uint32_t>(shared->width);
        params.inputFormat = 3;
        tile = std::min(kTile / params.width, std::size_t{512}) * params.width;
    }

    const auto start = Clock::now();
    auto getContext = [](bool interop) -> VulkanProjection* {
        if (interop) {
            thread_local VulkanProjection context;
            thread_local const bool ready = context.initialize(true);
            return ready ? &context : nullptr;
        }
        thread_local VulkanProjection context;
        thread_local const bool ready = context.initialize();
        return ready ? &context : nullptr;
    };
    auto* candidate = getContext(shared != nullptr);
    std::optional<SharedGlImage> bridge;
    if (shared && candidate) bridge.emplace();
    struct ReleaseImport {
        VulkanProjection* context;
        ~ReleaseImport() { if (context) context->discardShared(); }
    } release{shared ? candidate : nullptr};
    if (!candidate || (bridge && !bridge->create(*shared)) ||
        !candidate->prepare(response, readbackBytes, bridge ? &*bridge : nullptr)) {
        Logger::instance().log(LogLevel::kWarn, "engine", "spektrafilm.vulkan_hanatos_unavailable");
        return 0;
    }
    auto& context = *candidate;
    const auto setupMs = elapsedMs(start);
    double uploadMs = 0.0, waitMs = 0.0, downloadMs = 0.0, gpuMs = 0.0;
    if (bridge) {
        const auto step = Clock::now();
        if (!bridge->copy(*shared)) {
            Logger::instance().log(LogLevel::kWarn, "engine", "spektrafilm.vulkan_shared_copy_failed");
            return 0;
        }
        uploadMs = elapsedMs(step);
    }
    std::atomic<std::size_t> fp64Pixels{0};
    std::size_t done = 0;
    for (; done < pixels.size(); done += params.count) {
        const auto part = pixels.subspan(done, std::min(tile, pixels.size() - done));
        params.count = static_cast<std::uint32_t>(part.size());
        if (shared) params.firstRow = static_cast<std::uint32_t>(done / params.width);
        Pixel* const mapped = context.pixels();
        auto step = Clock::now();
        const bool inputReady = readback
            ? readback->read(done / readback->width, part.size() / readback->width, context.readbackTarget())
            : shared || parallelPixels(part.size(), workers, [&](std::size_t begin, std::size_t count) {
                for (std::size_t i = begin; i < begin + count; ++i) mapped[i] = pack(part[i]);
                return true;
            });
        if (!inputReady) break;
        uploadMs += elapsedMs(step);
        step = Clock::now();
        const bool completed = context.dispatch(params, gpuMs, done + part.size() == pixels.size());
        waitMs += elapsedMs(step);
        if (!completed) break;
        step = Clock::now();
        if (!parallelPixels(part.size(), workers, [&](std::size_t begin, std::size_t count) {
                std::size_t cpu = 0;
                std::size_t i = begin;
#if defined(__aarch64__)
                if (shared && shared->simd) {
                    // Exact binary32 → binary64 widening; preserve the whole-frame
                    // fallback on any flagged texel, including the scalar tail.
                    for (; begin + count - i >= 4; i += 4) {
                        const auto v = vld4q_f32(mapped[i].data());
                        if (vminvq_u32(vceqzq_f32(v.val[3])) == 0) return false;
                        const float64x2x3_t lo{{vcvt_f64_f32(vget_low_f32(v.val[0])),
                            vcvt_f64_f32(vget_low_f32(v.val[1])), vcvt_f64_f32(vget_low_f32(v.val[2]))}};
                        const float64x2x3_t hi{{vcvt_high_f64_f32(v.val[0]),
                            vcvt_high_f64_f32(v.val[1]), vcvt_high_f64_f32(v.val[2])}};
                        vst3q_f64(part[i].data(), lo);
                        vst3q_f64(part[i + 2].data(), hi);
                    }
                }
#endif
                for (; i < begin + count; ++i) {
                    const auto& out = mapped[i];
                    if (out[3] == 0.0f) {
                        part[i] = {out[0], out[1], out[2]};
                    } else {
                        if (readback || shared) return false;
                        const auto pixel = part.subspan(i, 1);
                        if (!projectHanatosRaw(pixel, pixel, xyzFromRgb, response, side, exposureGain)) return false;
                        ++cpu;
                    }
                }
                if (cpu) fp64Pixels.fetch_add(cpu, std::memory_order_relaxed);
                return true;
            })) {
            if (!readback && !shared) return std::nullopt;
            break;
        }
        downloadMs += elapsedMs(step);
    }
    char timing[400];
    std::snprintf(timing, sizeof(timing),
        "{\"pixels\":%zu,\"totalPixels\":%zu,\"fp64Pixels\":%zu,\"memoryFlags\":%u,\"inputFormat\":%u,\"simd\":%d,\"setupMs\":%.3f,"
        "\"uploadMs\":%.3f,\"waitMs\":%.3f,\"gpuMs\":%.3f,\"downloadMs\":%.3f,\"totalMs\":%.3f",
        done, pixels.size(), fp64Pixels.load(), context.memoryFlags(), params.inputFormat, shared && shared->simd,
        setupMs, uploadMs, waitMs, gpuMs,
        downloadMs, elapsedMs(start));
    Logger::instance().log(done == pixels.size() ? LogLevel::kInfo : LogLevel::kWarn,
        "engine", "spektrafilm.vulkan_hanatos", std::string(timing) +
        ",\"device\":\"" + jsonEscape(context.name()) + "\"}");
    return done;
}

}  // namespace filmkit::spektrafilm

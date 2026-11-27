#include "VkRun.h"
#include <GLES2/gl2ext.h>
#include <bit>

namespace filmkit::vkprobe {
namespace {
constexpr VkImageSubresourceRange color{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
constexpr auto samplerType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
constexpr auto storageType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
constexpr auto ahbHandle = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
}

GlImage::~GlImage() {
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &texture);
    if (egl != EGL_NO_IMAGE_KHR) destroy(display, egl);
    if (ahb) AHardwareBuffer_release(ahb);
}

bool GlImage::make(int w, int h, bool half, bool framebuffer) {
    display = eglGetCurrentDisplay();
    destroy = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    const auto client = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    const auto create = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    const auto target = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (display == EGL_NO_DISPLAY || !destroy || !client || !create || !target) return false;
    const AHardwareBuffer_Desc desc{.width=uint32_t(w), .height=uint32_t(h), .layers=1,
        .format=half ? AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT : AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage=AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE};
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0) return false;
    const EGLint attr[]{EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    egl = create(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client(ahb), attr);
    if (egl == EGL_NO_IMAGE_KHR) return false;
    GLint old = 0, draw = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old);
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    target(GL_TEXTURE_2D, egl);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    bool complete = true;
    if (framebuffer) {
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        complete = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
    }
    glBindTexture(GL_TEXTURE_2D, old);
    return glGetError() == GL_NO_ERROR && complete;
}

Run::~Run() {
    if (dev) {
        if (pending) vkDeviceWaitIdle(dev);
        for (const auto p : pipelines) vkDestroyPipeline(dev, p, nullptr);
        vkDestroyPipelineLayout(dev, layout, nullptr);
        vkDestroyDescriptorPool(dev, pool, nullptr);
        vkDestroyDescriptorSetLayout(dev, bindings, nullptr);
        vkDestroySampler(dev, sampler, nullptr);
        vkDestroyQueryPool(dev, queries, nullptr);
        vkDestroyFence(dev, fence, nullptr);
        vkDestroyCommandPool(dev, commands, nullptr);
        vkDestroyDevice(dev, nullptr);
    }
    if (instance) vkDestroyInstance(instance, nullptr);
}

bool Run::init(std::span<const VkDescriptorType> types, uint32_t pushBytes) {
    const VkApplicationInfo app{.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion=VK_API_VERSION_1_1};
    const VkInstanceCreateInfo ii{.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo=&app};
    uint32_t n=0;
    if (vkCreateInstance(&ii, nullptr, &instance) != VK_SUCCESS ||
        vkEnumeratePhysicalDevices(instance, &n, nullptr) != VK_SUCCESS || !n) return false;
    std::vector<VkPhysicalDevice> devices(n);
    if (vkEnumeratePhysicalDevices(instance, &n, devices.data()) != VK_SUCCESS) return false;
    for (auto p : devices) {
        vkGetPhysicalDeviceProperties(p, &props);
        if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU || props.apiVersion < VK_API_VERSION_1_1) continue;
        vkGetPhysicalDeviceQueueFamilyProperties(p, &n, nullptr);
        std::vector<VkQueueFamilyProperties> families(n);
        vkGetPhysicalDeviceQueueFamilyProperties(p, &n, families.data());
        for (uint32_t i=0; i<n; ++i) {
            if (families[i].queueCount && (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
                gpu=p; family=i; timestampBits=families[i].timestampValidBits; break;
            }
        }
        if (gpu) break;
    }
    if (!gpu || pushBytes > props.limits.maxPushConstantsSize) return false;
    const float priority=0;
    const VkDeviceQueueCreateInfo qi{.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex=family,
        .queueCount=1, .pQueuePriorities=&priority};
    const char* extensions[]{VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                             VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME};
    const VkDeviceCreateInfo di{.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount=1,
        .pQueueCreateInfos=&qi, .enabledExtensionCount=2, .ppEnabledExtensionNames=extensions};
    if (vkCreateDevice(gpu, &di, nullptr, &dev) != VK_SUCCESS) return false;
    vkGetDeviceQueue(dev, family, 0, &queue);
    std::vector<VkDescriptorSetLayoutBinding> b;
    uint32_t sampled=0, storage=0;
    for (auto t : types) {
        b.push_back({uint32_t(b.size()), t, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
        if (t == samplerType) ++sampled; else ++storage;
    }
    const VkDescriptorSetLayoutCreateInfo bi{.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount=uint32_t(b.size()), .pBindings=b.data()};
    if (vkCreateDescriptorSetLayout(dev, &bi, nullptr, &bindings) != VK_SUCCESS) return false;
    const VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes};
    const VkPipelineLayoutCreateInfo li{.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount=1,
        .pSetLayouts=&bindings, .pushConstantRangeCount=1, .pPushConstantRanges=&pr};
    if (vkCreatePipelineLayout(dev, &li, nullptr, &layout) != VK_SUCCESS) return false;
    const VkDescriptorPoolSize sizes[]{{storageType, storage}, {samplerType, sampled}};
    const VkDescriptorPoolCreateInfo pi{.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets=1,
        .poolSizeCount=sampled ? 2u : 1u, .pPoolSizes=sizes};
    const VkDescriptorSetAllocateInfo si{.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool=VK_NULL_HANDLE, .descriptorSetCount=1, .pSetLayouts=&bindings};
    if (vkCreateDescriptorPool(dev, &pi, nullptr, &pool) != VK_SUCCESS) return false;
    auto alloc=si;
    alloc.descriptorPool=pool;
    if (vkAllocateDescriptorSets(dev, &alloc, &set) != VK_SUCCESS) return false;
    const VkCommandPoolCreateInfo ci{.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex=family};
    const VkCommandBufferAllocateInfo ca{.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .level=VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount=1};
    if (vkCreateCommandPool(dev, &ci, nullptr, &commands) != VK_SUCCESS) return false;
    auto commandAlloc=ca;
    commandAlloc.commandPool=commands;
    const VkFenceCreateInfo fi{.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkAllocateCommandBuffers(dev, &commandAlloc, &cmd) != VK_SUCCESS ||
        vkCreateFence(dev, &fi, nullptr, &fence) != VK_SUCCESS) return false;
    if (timestampBits) {
        const VkQueryPoolCreateInfo ti{.sType=VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
            .queryType=VK_QUERY_TYPE_TIMESTAMP, .queryCount=2};
        if (vkCreateQueryPool(dev, &ti, nullptr, &queries) != VK_SUCCESS) queries={};
    }
    const VkSamplerCreateInfo sm{.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter=VK_FILTER_NEAREST, .minFilter=VK_FILTER_NEAREST, .addressModeU=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE};
    return !sampled || vkCreateSampler(dev, &sm, nullptr, &sampler) == VK_SUCCESS;
}

bool Run::pipeline(std::span<const uint32_t> words) {
    VkShaderModule shader{};
    const VkShaderModuleCreateInfo mi{.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize=words.size_bytes(), .pCode=words.data()};
    if (vkCreateShaderModule(dev, &mi, nullptr, &shader) != VK_SUCCESS) return false;
    const VkComputePipelineCreateInfo ci{.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage={.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage=VK_SHADER_STAGE_COMPUTE_BIT,
            .module=shader, .pName="main"}, .layout=layout};
    VkPipeline p{};
    const auto r=vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &ci, nullptr, &p);
    vkDestroyShaderModule(dev, shader, nullptr);
    if (p) pipelines.push_back(p);
    return r == VK_SUCCESS;
}

bool Run::alloc(Buffer& b, VkDeviceSize size, VkBufferUsageFlags usage, bool host) {
    const VkBufferCreateInfo bi{.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size=size, .usage=usage};
    if ((usage & VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) && size > props.limits.maxStorageBufferRange) return false;
    if (vkCreateBuffer(dev, &bi, nullptr, &b.handle) != VK_SUCCESS) return false;
    VkMemoryRequirements req{};
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetBufferMemoryRequirements(dev,b.handle,&req);
    vkGetPhysicalDeviceMemoryProperties(gpu,&mp);
    uint32_t chosen=mp.memoryTypeCount;
    int best=-1;
    for (uint32_t i=0; i<mp.memoryTypeCount; ++i) {
        const auto f=mp.memoryTypes[i].propertyFlags;
        if (!(req.memoryTypeBits & (1u<<i)) || (host && !(f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))) continue;
        const int score=host ? 2*bool(f & VK_MEMORY_PROPERTY_HOST_CACHED_BIT)+bool(f & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                             : int(bool(f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        if (score>best) { best=score; chosen=i; }
    }
    if (best<0) return false;
    const VkMemoryAllocateInfo ai{.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize=req.size, .memoryTypeIndex=chosen};
    b.size=size;
    b.coherent=mp.memoryTypes[chosen].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    return vkAllocateMemory(dev,&ai,nullptr,&b.memory)==VK_SUCCESS &&
        vkBindBufferMemory(dev,b.handle,b.memory,0)==VK_SUCCESS &&
        (!host || vkMapMemory(dev,b.memory,0,VK_WHOLE_SIZE,0,&b.map)==VK_SUCCESS);
}

void Run::free(Buffer& b) {
    if (pending) { vkDeviceWaitIdle(dev); pending=false; }
    if (b.map) vkUnmapMemory(dev,b.memory);
    vkDestroyBuffer(dev,b.handle,nullptr);
    vkFreeMemory(dev,b.memory,nullptr);
    b={};
}

bool Run::sync(const Buffer& b, bool read) const {
    const VkMappedMemoryRange mr{.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, .memory=b.memory, .size=VK_WHOLE_SIZE};
    return b.coherent || (read ? vkInvalidateMappedMemoryRanges(dev,1,&mr) : vkFlushMappedMemoryRanges(dev,1,&mr)) == VK_SUCCESS;
}

bool Run::import(Image& img, AHardwareBuffer* ahb, VkImageUsageFlags usage, bool sampled) {
    const auto get=reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(dev, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    VkAndroidHardwareBufferFormatPropertiesANDROID fmt{.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID ap{.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, .pNext=&fmt};
    if (!get || get(dev,ahb,&ap)!=VK_SUCCESS || !ap.memoryTypeBits ||
        (fmt.format!=VK_FORMAT_R16G16B16A16_SFLOAT && fmt.format!=VK_FORMAT_R8G8B8A8_UNORM) ||
        (sampled && !(fmt.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))) return false;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb,&desc);
    const VkExternalMemoryImageCreateInfo ei{.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .handleTypes=ahbHandle};
    const VkImageCreateInfo ii{.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,.pNext=&ei,.imageType=VK_IMAGE_TYPE_2D,
        .format=fmt.format,.extent={desc.width,desc.height,1},.mipLevels=1,.arrayLayers=1,
        .samples=VK_SAMPLE_COUNT_1_BIT,.tiling=VK_IMAGE_TILING_OPTIMAL,.usage=usage};
    if (vkCreateImage(dev,&ii,nullptr,&img.handle)!=VK_SUCCESS) return false;
    const VkImportAndroidHardwareBufferInfoANDROID importInfo{.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .buffer=ahb};
    const VkMemoryDedicatedAllocateInfo dedicated{.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext=&importInfo,.image=img.handle};
    const VkMemoryAllocateInfo ai{.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.pNext=&dedicated,
        .allocationSize=ap.allocationSize,.memoryTypeIndex=uint32_t(std::countr_zero(ap.memoryTypeBits))};
    if (vkAllocateMemory(dev,&ai,nullptr,&img.memory)!=VK_SUCCESS ||
        vkBindImageMemory(dev,img.handle,img.memory,0)!=VK_SUCCESS) return false;
    const VkImageViewCreateInfo vi{.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,.image=img.handle,
        .viewType=VK_IMAGE_VIEW_TYPE_2D,.format=fmt.format,.subresourceRange=color};
    return !sampled || vkCreateImageView(dev,&vi,nullptr,&img.view)==VK_SUCCESS;
}

void Run::free(Image& img) {
    if (pending) { vkDeviceWaitIdle(dev); pending=false; }
    vkDestroyImageView(dev,img.view,nullptr);
    vkDestroyImage(dev,img.handle,nullptr);
    vkFreeMemory(dev,img.memory,nullptr);
    img={};
}

void Run::bindBuffer(int i,const Buffer& b) const {
    const VkDescriptorBufferInfo bi{b.handle,0,b.size};
    const VkWriteDescriptorSet w{.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=set,.dstBinding=uint32_t(i),
        .descriptorCount=1,.descriptorType=storageType,.pBufferInfo=&bi};
    vkUpdateDescriptorSets(dev,1,&w,0,nullptr);
}

void Run::bindImage(int i,const Image& img) const {
    const VkDescriptorImageInfo ii{sampler,img.view,VK_IMAGE_LAYOUT_GENERAL};
    const VkWriteDescriptorSet w{.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=set,.dstBinding=uint32_t(i),
        .descriptorCount=1,.descriptorType=samplerType,.pImageInfo=&ii};
    vkUpdateDescriptorSets(dev,1,&w,0,nullptr);
}

bool Run::begin() {
    const VkCommandBufferBeginInfo bi{.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    if (pending || vkResetFences(dev,1,&fence)!=VK_SUCCESS || vkResetCommandPool(dev,commands,0)!=VK_SUCCESS ||
        vkBeginCommandBuffer(cmd,&bi)!=VK_SUCCESS) return false;
    vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);
    if (queries) { vkCmdResetQueryPool(cmd,queries,0,2); vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0); }
    return true;
}

void Run::dispatch(int p,const void* push,uint32_t n,int x,int y) const {
    vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipelines.at(p));
    vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,n,push);
    vkCmdDispatch(cmd,x,y,1);
}

void Run::barrier(VkPipelineStageFlags src,VkPipelineStageFlags dst,VkAccessFlags write,VkAccessFlags read) const {
    const VkMemoryBarrier mb{.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=write,.dstAccessMask=read};
    vkCmdPipelineBarrier(cmd,src,dst,0,1,&mb,0,nullptr,0,nullptr);
}

void Run::external(const Image& img,bool acquire,VkAccessFlags access,VkPipelineStageFlags stage) const {
    const VkImageMemoryBarrier ib{.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask=acquire ? 0u : access,.dstAccessMask=acquire ? access : 0u,
        .oldLayout=VK_IMAGE_LAYOUT_GENERAL,.newLayout=VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex=acquire ? VK_QUEUE_FAMILY_FOREIGN_EXT : family,
        .dstQueueFamilyIndex=acquire ? family : VK_QUEUE_FAMILY_FOREIGN_EXT,.image=img.handle,.subresourceRange=color};
    vkCmdPipelineBarrier(cmd,acquire ? VkPipelineStageFlags(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT) : stage,
        acquire ? stage : VkPipelineStageFlags(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT),0,0,nullptr,0,nullptr,1,&ib);
}

void Run::copyToImage(const Buffer& b,const Image& img,int w,int h,int y) const {
    const VkBufferImageCopy region{.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1},
        .imageOffset={0,y,0},.imageExtent={uint32_t(w),uint32_t(h),1}};
    vkCmdCopyBufferToImage(cmd,b.handle,img.handle,VK_IMAGE_LAYOUT_GENERAL,1,&region);
}

bool Run::finish() {
    if (queries) vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);
    if (vkEndCommandBuffer(cmd)!=VK_SUCCESS) return false;
    const VkSubmitInfo si{.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&cmd};
    if (vkQueueSubmit(queue,1,&si,fence)!=VK_SUCCESS) return false;
    pending=true;
    if (vkWaitForFences(dev,1,&fence,VK_TRUE,5'000'000'000ull)!=VK_SUCCESS) return false;
    pending=false;
    return true;
}

double Run::gpuMs() const {
    uint64_t t[2]{};
    if (!queries || vkGetQueryPoolResults(dev,queries,0,2,sizeof(t),t,sizeof(t[0]),VK_QUERY_RESULT_64_BIT)!=VK_SUCCESS) return 0;
    return ((t[1]-t[0]) & (~uint64_t(0) >> (64-timestampBits)))*double(props.limits.timestampPeriod)/1e6;
}

} // namespace filmkit::vkprobe

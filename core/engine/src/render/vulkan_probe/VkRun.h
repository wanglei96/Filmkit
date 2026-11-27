#pragma once

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <span>
#include <vector>

namespace filmkit::vkprobe {

struct GlImage {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLImageKHR egl = EGL_NO_IMAGE_KHR;
    PFNEGLDESTROYIMAGEKHRPROC destroy = nullptr;
    AHardwareBuffer* ahb = nullptr;
    GLuint texture = 0, fbo = 0;
    ~GlImage();
    bool make(int w, int h, bool half, bool framebuffer);
    GLuint takeTexture() { const auto result = texture; texture = 0; return result; }
};

struct Run {
    struct Buffer {
        VkBuffer handle = {};
        VkDeviceMemory memory = {};
        VkDeviceSize size = 0;
        void* map = nullptr;
        bool coherent = false;
    };
    struct Image {
        VkImage handle = {};
        VkDeviceMemory memory = {};
        VkImageView view = {};
    };

    VkInstance instance = {};
    VkPhysicalDevice gpu = {};
    VkPhysicalDeviceProperties props{};
    VkDevice dev = {};
    VkQueue queue = {};
    uint32_t family = 0, timestampBits = 0;
    VkCommandPool commands = {};
    VkCommandBuffer cmd = {};
    VkFence fence = {};
    VkQueryPool queries = {};
    VkSampler sampler = {};
    VkDescriptorSetLayout bindings = {};
    VkDescriptorPool pool = {};
    VkDescriptorSet set = {};
    VkPipelineLayout layout = {};
    std::vector<VkPipeline> pipelines;
    bool pending = false;

    ~Run();
    bool init(std::span<const VkDescriptorType> types, uint32_t pushBytes);
    bool pipeline(std::span<const uint32_t> words);
    bool alloc(Buffer&, VkDeviceSize, VkBufferUsageFlags, bool host);
    void free(Buffer&);
    bool sync(const Buffer&, bool read) const;
    bool import(Image&, AHardwareBuffer*, VkImageUsageFlags, bool sampled);
    void free(Image&);
    void bindBuffer(int index, const Buffer&) const;
    void bindImage(int index, const Image&) const;
    bool begin();
    void dispatch(int pipe, const void* push, uint32_t bytes, int x, int y) const;
    void barrier(VkPipelineStageFlags src, VkPipelineStageFlags dst, VkAccessFlags write, VkAccessFlags read) const;
    void external(const Image&, bool acquire, VkAccessFlags access, VkPipelineStageFlags stage) const;
    void copyToImage(const Buffer&, const Image&, int w, int h, int y = 0) const;
    bool finish();
    double gpuMs() const;
};

} // namespace filmkit::vkprobe

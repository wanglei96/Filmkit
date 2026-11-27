#include "StillGpuProbe.h"
#include "VkRun.h"
#include "log/Logger.h"
#include <chrono>
#include <cstdlib>
#include <string>

namespace filmkit::vkprobe {
namespace {
constexpr uint32_t direction[] = {
#include "Rcd1.inc"
};
constexpr uint32_t directionTiled[] = {
#include "Rcd1Tile.inc"
};
constexpr uint32_t green[] = {
#include "Rcd2.inc"
};
using Clock = std::chrono::steady_clock;
double ms(Clock::time_point start) {
    return std::chrono::duration<double,std::milli>(Clock::now()-start).count();
}
constexpr auto compute = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
constexpr auto transfer = VK_PIPELINE_STAGE_TRANSFER_BIT;

struct RcdRun : Run {
    Buffer dir, g;
    bool ready = false;
    ~RcdRun() { if (dev) { free(dir); free(g); } }
    bool prepare(int w, int h) {
        if (!ready) {
            if (instance) return false;
            constexpr VkDescriptorType types[]{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
            ready=init(types,16) && pipeline(direction) && pipeline(directionTiled) && pipeline(green);
        }
        if (!ready) return false;
        const auto size=VkDeviceSize(w)*h*8;
        if (dir.size==size && g.size==size && dir.memory && g.memory) return true;
        free(dir); free(g);
        constexpr auto usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        return alloc(dir,size,usage,false) && alloc(g,size,usage,false);
    }
};

struct RcdImages {
    explicit RcdImages(Run& r) : run(r) {}
    Run& run;
    GlImage source, dir, g;
    Run::Image input, outputDir, outputG;
    ~RcdImages() { run.free(input); run.free(outputDir); run.free(outputG); }
};
struct GlBlit {
    GLint draw=0, read=0;
    GLboolean scissor=glIsEnabled(GL_SCISSOR_TEST);
    GlBlit() {
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING,&draw);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING,&read);
        glDisable(GL_SCISSOR_TEST);
    }
    ~GlBlit() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
        if (scissor) glEnable(GL_SCISSOR_TEST);
    }
    static void copy(GLuint from,GLuint to,int w,int h) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,from);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,to);
        glBlitFramebuffer(0,0,w,h,0,0,w,h,GL_COLOR_BUFFER_BIT,GL_NEAREST);
    }
};
}

bool mode(const char* name) {
    const auto* v = std::getenv(name);
    return v && (*v=='1' || *v=='2' || *v=='3') && !v[1];
}

RcdOutput::~RcdOutput() {
    if (dir) glDeleteTextures(1,&dir);
    if (green) glDeleteTextures(1,&green);
}

bool rcd(GLuint mosaic, GLuint dirFbo, GLuint greenFbo,
         int w,int h,int cfaLut,float greenOriginal,RcdOutput& output) {
    const auto t0=Clock::now();
    thread_local RcdRun run;
    if (!run.prepare(w,h)) return false;
    const char variant=std::getenv("FILMKIT_VK_RCD")[0];
    const bool direct=variant=='3';
    RcdImages img{run};
    constexpr auto usage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!img.source.make(w,h,true,true) || !img.dir.make(w,h,true,!direct) || !img.g.make(w,h,true,!direct) ||
        !run.import(img.input,img.source.ahb,usage,true) ||
        !run.import(img.outputDir,img.dir.ahb,usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT,false) ||
        !run.import(img.outputG,img.g.ahb,usage | VK_IMAGE_USAGE_TRANSFER_DST_BIT,false)) return false;
    const auto setupMs=ms(t0);
    GlBlit gl;
    GLuint fbo=0;
    glGenFramebuffers(1,&fbo);
    glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,mosaic,0);
    GLint oldTex=0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D,&oldTex);
    glBindTexture(GL_TEXTURE_2D,mosaic);
    GLint format=0;
    glGetTexLevelParameteriv(GL_TEXTURE_2D,0,GL_TEXTURE_INTERNAL_FORMAT,&format);
    glBindTexture(GL_TEXTURE_2D,oldTex);
    const bool complete=format==GL_RGBA16F && glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE;
    if (complete) gl.copy(fbo,img.source.fbo,w,h);
    glDeleteFramebuffers(1,&fbo);
    if (!complete) return false;
    const auto done=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
    if (!done) return false;
    const auto status=glClientWaitSync(done,GL_SYNC_FLUSH_COMMANDS_BIT,5'000'000'000ull);
    glDeleteSync(done);
    if (glGetError()!=GL_NO_ERROR || (status!=GL_ALREADY_SIGNALED && status!=GL_CONDITION_SATISFIED)) return false;
    const auto inputMs=ms(t0)-setupMs;
    run.bindImage(0,img.input);
    run.bindBuffer(1,run.dir);
    run.bindBuffer(2,run.g);
    if (!run.begin()) return false;
    struct Push { int w,h,cfa; float orig; };
    const Push p{w,h,cfaLut,greenOriginal};
    const int tiled=variant=='2';
    run.external(img.input,true,VK_ACCESS_SHADER_READ_BIT,compute);
    run.dispatch(tiled,&p,sizeof(p),(w+15)/16,(h+7)/8);
    run.barrier(compute,compute,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT);
    run.dispatch(2,&p,sizeof(p),(w+15)/16,(h+7)/8);
    run.barrier(compute,transfer,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
    for (const auto* dst : {&img.outputDir,&img.outputG}) run.external(*dst,true,VK_ACCESS_TRANSFER_WRITE_BIT,transfer);
    run.copyToImage(run.dir,img.outputDir,w,h);
    run.copyToImage(run.g,img.outputG,w,h);
    for (const auto* dst : {&img.outputDir,&img.outputG}) run.external(*dst,false,VK_ACCESS_TRANSFER_WRITE_BIT,transfer);
    run.external(img.input,false,VK_ACCESS_SHADER_READ_BIT,compute);
    if (!run.finish()) return false;
    const auto gpuMs=run.gpuMs();
    if (direct) {
        output.dir=img.dir.takeTexture();
        output.green=img.g.takeTexture();
    } else {
        gl.copy(img.dir.fbo,dirFbo,w,h);
        gl.copy(img.g.fbo,greenFbo,w,h);
        // Cover the actual handback, not just the submission of the blits.
        glFinish();
        if (glGetError()!=GL_NO_ERROR) return false;
    }
    Logger::instance().log(LogLevel::kInfo,"engine","vkprobe.rcd",
        "{\"tile\":"+std::to_string(tiled)+",\"direct\":"+std::to_string(direct)+",\"setupMs\":"+std::to_string(setupMs)+
        ",\"inputMs\":"+std::to_string(inputMs)+",\"gpuMs\":"+std::to_string(gpuMs)+
        ",\"totalMs\":"+std::to_string(ms(t0))+"}");
    return true;
}

} // namespace filmkit::vkprobe

#include "StillGpuProbe.h"
#include "VkRun.h"
#include "film/spektrafilm/ParallelPixels.h"
#include "log/Logger.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <future>
#include <memory>
#include <string>

namespace filmkit::vkprobe {
namespace {
thread_local ScanJob* activeScan = nullptr;
constexpr uint32_t shader[] = {
#include "ScanProbe.inc"
};
using Clock=std::chrono::steady_clock;
double ms(Clock::time_point t) {
    return std::chrono::duration<double,std::milli>(Clock::now()-t).count();
}
constexpr int kStripe=512, kNodes=65*65*65, kMaskWords=8192;
constexpr auto compute=VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
constexpr auto transfer=VK_PIPELINE_STAGE_TRANSFER_BIT;
constexpr auto storage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
constexpr auto copySource=VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

struct Push {
    Float4 scanToRgb[3];
    int w,h,inputY,inputRows;
    int outputY,outputRows,tenBit,useLut;
    int lensRadius,usmRadius,stage,errorOffset;
    float amount;
};
static_assert(sizeof(Push)==100);
struct Tables {
    Float4 adaptationFl, nbbZAwScale, view;
    float weights[38], lightness[64], cmax[46080];
    uint32_t good[kMaskWords];
};
static_assert(sizeof(Tables)==48+(38+64+46080+kMaskWords)*4);

struct ScanRun : Run {
    Buffer input,color,temp,lens,result,base,nodes,tables;
    bool ready=false, lutReady=false;
    ~ScanRun() {
        if (!dev) return;
        for (auto* b : {&input,&color,&temp,&lens,&result,&base,&nodes,&tables}) free(*b);
    }

    bool setup() {
        if (instance) return ready;
        constexpr auto s=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        constexpr VkDescriptorType types[]{s,s,s,s,s,s,s,s};
        ready=init(types,sizeof(Push)) && pipeline(shader) &&
            alloc(nodes,VkDeviceSize(kNodes)*sizeof(Float4),storage,true) &&
            alloc(tables,sizeof(Tables),storage,true);
        return ready;
    }

    bool prepare(int w,int inputRows,int outputRows,bool blur,bool sharpen,bool tenBit,const ScanParams& p) {
        if (!setup()) return false;
        const auto size=VkDeviceSize(w)*inputRows*sizeof(Float4);
        const auto packed=(VkDeviceSize(w)*outputRows+1)*sizeof(uint32_t);
        const auto resize=[&](Buffer& b,VkDeviceSize n,VkBufferUsageFlags usage,bool host=false) {
            if (b.memory && b.size>=n && (!host || b.map)) return true;
            free(b);
            return alloc(b,n,usage,host);
        };
        if (!resize(input,size,storage,true) || !resize(color,size,storage) ||
            !resize(temp,blur||sharpen ? size : sizeof(Float4),storage) ||
            !resize(lens,blur ? size : sizeof(Float4),storage) ||
            !resize(result,packed,storage|copySource,true) ||
            !resize(base,tenBit ? packed : sizeof(uint32_t),storage|copySource)) return false;
        const std::array buffers{&input,&color,&temp,&lens,&result,&base,&nodes,&tables};
        for (int i=0; i<int(buffers.size()); ++i) bindBuffer(i,*buffers[i]);
        if (!lutReady && !p.lutNodes.empty()) {
            if (p.lutNodes.size()!=kNodes*sizeof(Triplet)) return false;
            auto* pixels=static_cast<Float4*>(nodes.map);
            for (int i=0;i<kNodes;++i) {
                Triplet v;
                std::memcpy(&v,p.lutNodes.data()+i*sizeof(v),sizeof(v));
                pixels[i]={float(v[0]),float(v[1]),float(v[2]),0};
            }
            if (!sync(nodes,false)) return false;
            lutReady=true;
        }
        return true;
    }
};

thread_local std::unique_ptr<ScanRun> scanRun;
thread_local std::future<std::unique_ptr<ScanRun>> prewarm;

int radius(double sigma) { return int(3.0*sigma+0.5); }

void weights(float* dst,double sigma,int r) {
    if (r==0) return;
    double cache[19]{},total=0;
    for (int i=-r;i<=r;++i) {
        const double x=double(i)/sigma;
        cache[i+r]=std::exp(-0.5*(x*x));
        total+=cache[i+r];
    }
    for (int i=0;i<=2*r;++i) dst[i]=float(cache[i]/total);
}
} // namespace

ScanJob::ScanJob(bool isTenBit) : tenBit(isTenBit),previous_(activeScan) { activeScan=this; }
ScanJob::~ScanJob() {
    close();
    if (hdrBase) glDeleteTextures(1,&hdrBase);
}
void ScanJob::close() { if (activeScan==this) activeScan=previous_; }
ScanJob* ScanJob::current() { return activeScan; }

void warmScan() {
    const auto* v = std::getenv("FILMKIT_VK_SCAN");
    if (!v || std::strcmp(v,"2")!=0 || scanRun || prewarm.valid()) return;
    prewarm=std::async(std::launch::async,[]{
        const auto t=Clock::now();
        auto run=std::make_unique<ScanRun>();
        const bool ok=run->setup();
        Logger::instance().log(ok?LogLevel::kInfo:LogLevel::kWarn,"engine","vkprobe.scan_warm",
            "{\"ms\":"+std::to_string(ms(t))+"}");
        return run;
    });
}

bool scan(std::span<const Triplet> xyz,int w,int h,const ScanParams& p,ScanJob& job) {
    if (xyz.size()<1024*1024 || w<2 || h<2 || (w&1) || xyz.size()!=std::size_t(w)*h ||
        !(p.blur>=0 && p.blur<3) || !(p.sharpen>=0 && p.sharpen<3) || !(p.amount>=0) ||
        (!p.lutMask.empty() && p.lutMask.size()!=kMaskWords*32)) return false;
    const auto t=Clock::now();
    const int blur=radius(p.blur), sharpen=p.amount>0 ? radius(p.sharpen) : 0;
    const int maxRows=std::min(h,kStripe),halo=blur+sharpen;
    if (!scanRun) {
        scanRun=prewarm.valid() ? prewarm.get() : std::make_unique<ScanRun>();
    }
    auto& run=*scanRun;
    if (!run.prepare(w,std::min(h,maxRows+2*halo),maxRows,blur>0,sharpen>0,job.tenBit,p)) return false;
    auto& table=*static_cast<Tables*>(run.tables.map);
    table.adaptationFl=p.adaptationFl; table.nbbZAwScale=p.nbbZAwScale; table.view=p.view;
    weights(table.weights,p.blur,blur); weights(table.weights+19,p.sharpen,sharpen);
    std::copy(p.lightness.begin(),p.lightness.end(),table.lightness);
    std::copy(p.cmax.begin(),p.cmax.end(),table.cmax);
    if (!p.lutMask.empty()) {
        for (int i=0;i<kMaskWords;++i) {
            uint32_t bits=0;
            for (int j=0;j<32;++j) bits|=uint32_t(p.lutMask[i*32+j]!=0)<<j;
            table.good[i]=bits;
        }
    }
    if (!run.sync(run.tables,false)) return false;
    GlImage gl;
    struct Imported {
        Run& run;
        Run::Image image{};
        ~Imported() { run.free(image); }
    } hdr{run};
    constexpr auto usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (!gl.make(w,h,false,false) || !run.import(hdr.image,gl.ahb,usage,false)) return false;
    std::vector<uint8_t> packed(xyz.size()*4);
    const double setup=ms(t);
    double upload=0,gpu=0,wait=0,read=0;
    const int error=maxRows*w;
    for (int y=0;y<h;y+=maxRows) {
        const auto begin=Clock::now();
        const int rows=std::min(maxRows,h-y), inputY=std::max(0,y-halo), end=std::min(h,y+rows+halo);
        Push push{{p.scanToRgb[0],p.scanToRgb[1],p.scanToRgb[2]},w,h,inputY,end-inputY,
            y,rows,int(job.tenBit),int(!p.lutMask.empty()),blur,sharpen,0,error,float(p.amount)};
        auto* dst=static_cast<Float4*>(run.input.map);
        const auto* src=xyz.data()+std::size_t(inputY)*w;
        if (!filmkit::spektrafilm::parallelPixelIndices(std::size_t(push.inputRows)*w,8,[&](auto i){
            const auto& v=src[i];
            dst[i]={float(v[0]),float(v[1]),float(v[2]),0};
        })) return false;
        auto* out=static_cast<uint32_t*>(run.result.map);
        out[error]=0;
        if (!run.sync(run.input,false) || !run.sync(run.result,false) || !run.begin()) return false;
        if (y==0) run.external(hdr.image,true,VK_ACCESS_TRANSFER_WRITE_BIT,transfer);
        constexpr auto stages=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        const auto dispatch=[&](int stage,int cols,int lines) {
            push.stage=stage;
            run.dispatch(0,&push,sizeof(push),(cols+15)/16,(lines+7)/8);
            if (stage!=4) run.barrier(compute,compute,VK_ACCESS_SHADER_WRITE_BIT,stages);
        };
        dispatch(0,w/2,push.inputRows);
        if (blur) { dispatch(1,w,push.inputRows); dispatch(2,w,push.inputRows); }
        if (sharpen) dispatch(3,w,push.inputRows);
        dispatch(4,w,rows);
        run.barrier(compute,transfer,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
        run.copyToImage(job.tenBit ? run.base : run.result,hdr.image,w,rows,h-y-rows);
        if (y+rows==h) run.external(hdr.image,false,VK_ACCESS_TRANSFER_WRITE_BIT,transfer);
        run.barrier(compute,VK_PIPELINE_STAGE_HOST_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT);
        const auto submitted=Clock::now();
        upload+=ms(begin);
        if (!run.finish()) return false;
        wait+=ms(submitted);
        gpu+=run.gpuMs();
        const auto readStart=Clock::now();
        if (!run.sync(run.result,true) || out[error]) return false;
        std::memcpy(packed.data()+std::size_t(h-y-rows)*w*4,out,std::size_t(w)*rows*4);
        read+=ms(readStart);
    }
    job.bytes.swap(packed);
    job.hdrBase=gl.takeTexture();
    Logger::instance().log(LogLevel::kInfo,"engine","vkprobe.scan",
        "{\"bits\":"+std::to_string(job.tenBit?10:8)+",\"lensRadius\":"+std::to_string(blur)+
        ",\"usmRadius\":"+std::to_string(sharpen)+",\"lut\":"+std::to_string(!p.lutMask.empty())+
        ",\"setupMs\":"+std::to_string(setup)+",\"uploadMs\":"+std::to_string(upload)+
        ",\"gpuMs\":"+std::to_string(gpu)+",\"waitMs\":"+std::to_string(wait)+
        ",\"readMs\":"+std::to_string(read)+",\"totalMs\":"+std::to_string(ms(t))+"}");
    return true;
}

} // namespace filmkit::vkprobe

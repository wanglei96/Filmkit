#include "../core/engine/src/render/LocalNoisePropagation.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <numeric>
#include <limits>

using namespace filmkit;
static void near(double a, double b, double e = 1e-10) {
  assert(std::isfinite(a) && std::isfinite(b));
  assert(std::abs(a-b) <= e * std::max(1.0, std::abs(b)));
}
int main() {
  LocalNoiseValue value;
  const LocalNoiseTap a{100, .3, .04, .001, 2, 1};
  assert(localNoiseMerge({a,a}, &value));
  near(value.signal,.6);
  near(value.variance,4*(.04*.3+.001)); // duplicate is NOT sqrt(2) improvement
  near(value.shot,.08);
  near(value.read,.004);
  auto b=a; b.source=101;
  assert(localNoiseMerge({a,b}, &value));
  near(value.variance,2*(.04*.3+.001));
  double cross;
  assert(localNoiseCrossCovariance({a,b},{a},&cross));
  near(cross,2*(.04*.3+.001));
  b.gain=3;
  assert(localNoiseCrossCovariance({a},{b},&cross)); near(cross,0);
  b.source=a.source;
  assert(localNoiseCrossCovariance({a},{b},&cross));
  near(cross,6*(.04*.3+.001));
  b.signal=.4;
  assert(!localNoiseMerge({a,b},&value));
  // Textured signal-specific V does not collapse into mean(A)*mean(signal)+B.
  b.source=200; b.weight=3; b.gain=4;
  assert(localNoiseMerge({a,b},&value));
  assert(std::abs(value.variance-value.shot*value.signal-value.read)>1e-4);
  const uint8_t cfas[]={0x94,0x61,0x49,0x16};
  for (uint8_t cfa:cfas) {
    std::vector<LocalNoiseObservation> obs;
    for(int phase=0;phase<4;++phase)
      obs.push_back({{.2, double(phase+1), .02, .001},phase,double(phase+1)});
    LocalNoiseRgbModel rgb;
    assert(localNoiseReduce(obs,cfa,&rgb));
    double gv=0,gw=0;
    for(int phase=0;phase<4;++phase) if(((cfa>>(2*phase))&3)==1) {
      gv+=(phase+1)*(phase+1); gw+=phase+1;
    }
    near(rgb.variance[1],gv/gw); // BOTH green phases and coverage, no squaring
  }
  LocalNoiseRgbModel rgb;
  assert(!localNoiseReduce({},0x94,&rgb));
  assert(!localNoiseReduce({},0xff,&rgb));
  const LocalNoiseCovariance camera={1,.2,-.1, .2,2,.3, -.1,.3,3};
  const std::array<double,3> lsc={2,3,4};
  const std::array<double,9> ccm={1.5,-.4,-.1, -.2,1.3,-.1, -.1,-.6,1.7};
  LocalNoiseCovariance result;
  assert(localNoiseToD4(camera,lsc,ccm,&result));
  for(int i=0;i<3;++i) for(int j=0;j<3;++j) {
    double expected=0;
    for(int k=0;k<3;++k) for(int m=0;m<3;++m)
      expected+=ccm[3*i+k]*lsc[k]*camera[3*k+m]*lsc[m]*ccm[3*j+m];
    near(result[3*i+j],expected);
  }
  auto delta=localNoiseDifferenceCovariance(camera,camera,camera);
  for(double x:delta) near(x,0);
  const std::array<double,3> luma={.2126,.7152,.0722};
  assert(localNoiseProjectedVariance(result,luma)>0);
  LocalNoiseCovariance bound;
  assert(localNoiseLinearCovarianceBound({camera,camera},{.25,.75},&bound));
  for(int k=0;k<9;++k) near(bound[k],camera[k]);
  assert(localNoiseLinearCovarianceBound({camera,camera},{2,-1},&bound));
  for(int k=0;k<9;++k) near(bound[k],9*camera[k]);
  assert(!localNoiseLinearCovarianceBound({camera},{1,2},&bound));
  LocalNoiseCorrelation corr;
  assert(localNoiseCorrelationFromDiagnostics({1,1,1},{0,0,0},&corr));
  for(double x:corr.spectrum) near(x,1);
  for(double ratio:{.00010001,.01,.1,.2}) {
    assert(localNoiseCorrelationFromDiagnostics({1,1,1},{0,ratio*4,0},&corr));
    near(std::accumulate(corr.spectrum.begin(),corr.spectrum.end(),0.0)/128,1);
    for(int i=0;i<128;++i) near(corr.spectrum[i],corr.spectrum[127-i]);
  }
  assert(!localNoiseCorrelationFromDiagnostics({1,-1,1},{0,1,0},&corr));
  for(int rotation:{0,90,180,270}) {
    const int w=8,h=6,dw=rotation%180?h:w,dh=rotation%180?w:h;
    std::vector<bool> visited(w*h,false);
    for(int y=0;y<dh;++y) for(int x=0;x<dw;++x) {
      int sx,sy;
      assert(localNoiseNativeCoordinate(x,y,w,h,rotation,&sx,&sy));
      assert(!visited[sy*w+sx]); visited[sy*w+sx]=true;
    }
    for(bool yes:visited) assert(yes);
  }
  std::cout << "PASS local noise: source duplicates/pair covariance, CFA weighted RGB, full LSC/CCM covariance, 128-bin PC correlation, rotations\n";
}

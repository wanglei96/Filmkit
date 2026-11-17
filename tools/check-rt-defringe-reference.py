#!/usr/bin/env python3
"""Evaluate pinned RawTherapee Lab defringe, not FilmKit's old RGB approximation.

Downloads and compiles original scalar Gaussian/curve/PF bodies into the output
directory. This is the isolated Lab module, not a RawTherapee development/JPEG
comparison. No production algorithm is changed. Requires C++, numpy and Pillow.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import urllib.request

import numpy as np
from PIL import Image

REVISION = '64c2d90a3248c7ad41842d9162368858b61aac6a'
BASE = f'https://raw.githubusercontent.com/RawTherapee/RawTherapee/{REVISION}/rtengine/'
RGB_XYZ = np.array([[.6369580483, .1446169036, .1688809752],
                    [.2627002120, .6779980715, .0593017165],
                    [0., .0280726930, 1.0609850577]])
BRADFORD = np.array([[1.0479298208, .0229467933, -.0501922295],
                     [.0296278157, .9904344846, -.0170738250],
                     [-.0092430582, .0150551449, .7518742814]])
WHITE = BRADFORD @ (RGB_XYZ @ np.ones(3))
RGB_D50 = BRADFORD @ RGB_XYZ


def block(source, start):
    """Extract balanced C++ function/class unchanged, ignoring comment braces."""
    assert source.count(start) == 1, start
    begin = source.index(start)
    clean = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"',
                   lambda m: ' ' * len(m[0]), source)
    first = clean.index('{', begin)
    depth = 0
    for end in range(first, len(clean)):
        depth += (clean[end] == '{') - (clean[end] == '}')
        if depth == 0:
            return source[begin:end + 1]
    raise ValueError(f'unclosed C++ block: {start}')


ADAPTER = r'''
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <vector>
#define BENCHFUN
#define ALIGNED16 __attribute__((aligned(16)))
#define CURVES_MIN_POLY_POINTS 1000
namespace rtengine {
template<class T> inline T SQR(T x) { return x*x; }
template<class T> inline T max(T a, T b) { return std::max(a,b); }
class CurveFactory {
public:
 static double clower(double,double,double), cupper(double,double,double);
 static double basel_alt(double), baseu_alt(double);
};
// Scalar libm atan2 instead of RT's SLEEF approximation: not bit parity.
inline float xatan2f(float y, float x) { return std::atan2(y,x); }
template<class T> struct JaggedArray {
 std::vector<T> data;
 std::vector<T*> rows;
 int w, h;
 JaggedArray(int width, int height):data(width*height),rows(height),w(width),h(height) {
  for(int y=0;y<h;++y) rows[y]=data.data()+y*w;
 }
 JaggedArray(const JaggedArray& o):JaggedArray(o.w,o.h) { data=o.data;
  for(int y=0;y<h;++y) rows[y]=data.data()+y*w;
 }
 T* operator[](int y) { return rows[y]; }
 const T* operator[](int y) const { return rows[y]; }
 operator T**() { return rows.data(); }
};
struct LabImage {
 int W,H;
 JaggedArray<float> a,b;
 LabImage(int w,int h):W(w),H(h),a(w,h),b(w,h) {}
 LabImage(const LabImage& o,bool):W(o.W),H(o.H),a(o.a),b(o.b) {}
};
'''

DRIVER = r'''
struct Params { DefringeParams defringe; };
class ImProcFunctions {
public:
 Params* params;
 bool multiThread=false;
 void PF_correct_RT(LabImage*,double,int);
};
'''

MAIN = r'''
} // namespace rtengine
int main(int argc,char** argv) {
 try {
  if(argc!=8) throw std::runtime_error("input output w h radius threshold hue(default/neutral)");
  int w=std::stoi(argv[3]),h=std::stoi(argv[4]);
  double radius=std::stod(argv[5]); int threshold=std::stoi(argv[6]);
  if(w<16 || h<16 || radius<.6 || radius>10 || threshold<0)
   throw std::runtime_error("reference harness domain violation");
  std::vector<float> source(static_cast<size_t>(w)*h*3);
  std::ifstream input(argv[1],std::ios::binary);
  if(!input.read(reinterpret_cast<char*>(source.data()),source.size()*sizeof(float)))
   throw std::runtime_error("short input");
  rtengine::LabImage lab(w,h);
  for(int i=0;i<w*h;++i) { lab.a.data[i]=source[3*i+1];lab.b.data[i]=source[3*i+2]; }
  rtengine::Params params;
  if(std::string(argv[7])=="neutral") params.defringe.huecurve.clear();
  else if(std::string(argv[7])!="default") throw std::runtime_error("unknown hue configuration");
  rtengine::ImProcFunctions process; process.params=&params;
  process.PF_correct_RT(&lab,radius,threshold);
  for(int i=0;i<w*h;++i) { source[3*i+1]=lab.a.data[i];source[3*i+2]=lab.b.data[i]; }
  std::ofstream output(argv[2],std::ios::binary);
  if(!output.write(reinterpret_cast<const char*>(source.data()),source.size()*sizeof(float)))
   throw std::runtime_error("output failed");
 } catch(const std::exception& e) { std::fprintf(stderr,"%s\n",e.what());return 1; }
}
'''


def build(folder):
    names = ('PF_correct_RT.cc', 'gauss.cc', 'curves.h', 'curves.cc',
             'flatcurves.cc', 'flatcurvetypes.h', 'color.h', 'procparams.cc')
    sources, manifest = {}, {}
    folder.mkdir(parents=True, exist_ok=True)
    for name in names:
        path = folder / name
        if not path.exists():
            path.write_bytes(urllib.request.urlopen(BASE + name, timeout=60).read())
        data = path.read_bytes()
        sources[name] = data.decode()
        manifest[name] = {'url': BASE + name, 'sha256': hashlib.sha256(data).hexdigest()}
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    gauss = sources['gauss.cc']
    gaussian = '\n'.join(block(gauss, name) for name in (
        'template<class T> void calculateYvVFactors(',
        'template<class T> void gaussHorizontal (',
        'template<class T> void gaussVertical ('))
    gaussian += '''
void gaussianBlur(float** src,float** dst,int w,int h,double sigma) {
 // Original scalar GAUSS_STANDARD dispatch for the tested sigma >= .6.
 gaussHorizontal<float>(src,dst,w,h,sigma);
 gaussVertical<float>(dst,dst,w,h,sigma);
}
'''
    curve_header = sources['curves.h']
    curves = block(curve_header, 'class Curve\n{') + ';\n'
    curves += block(curve_header, 'class FlatCurve final : public Curve') + ';\n'
    curves += '\n'.join(block(sources['curves.cc'], key) for key in (
        'Curve::Curve()', 'void Curve::AddPolygons()', 'void Curve::fillDyByDx()',
        'void Curve::fillHash()', 'int Curve::getSize()', 'void Curve::getControlPoint('))
    curves += sources['flatcurves.cc'].split('namespace rtengine\n{', 1)[1].rsplit('}', 1)[0]
    color = 'struct Color {\n' + block(sources['color.h'],
            'static inline double huelab_to_huehsv2 (') + '\n};\n'
    defaults = '''
struct DefringeParams {
 bool enabled; double radius; int threshold; std::vector<double> huecurve;
 DefringeParams();
};
'''
    # The constructor's first brace is the huecurve initializer, then its empty body.
    ctor_start = sources['procparams.cc'].index('DefringeParams::DefringeParams()')
    ctor_end = sources['procparams.cc'].index('bool DefringeParams::operator ==', ctor_start)
    defaults += sources['procparams.cc'][ctor_start:ctor_end]
    enum = block(sources['flatcurvetypes.h'], 'enum FlatCurveType') + ';\n'
    code = (sources['PF_correct_RT.cc'][:sources['PF_correct_RT.cc'].index('#include')]
            + ADAPTER + enum + gaussian + curves + color + defaults + DRIVER
            + block(sources['PF_correct_RT.cc'], 'void ImProcFunctions::PF_correct_RT(')
            + MAIN)
    cpp = folder / 'reference.cpp'
    cpp.write_text(code)
    binary = folder / 'reference'
    subprocess.run(['c++', '-O2', '-std=c++17', '-U__SSE2__', str(cpp), '-o', str(binary)], check=True)
    return binary


def to_lab(rgb):
    xyz = (np.asarray(rgb, 'f8') @ RGB_D50.T) / WHITE
    d = 6 / 29
    f = np.where(xyz > d**3, np.cbrt(xyz), xyz / (3*d*d) + 4/29)
    # RT Lab uses 327.68 units per CIELAB unit. No HDR clipping or RGB gamut clamp.
    return (np.stack((116*f[..., 1]-16, 500*(f[..., 0]-f[..., 1]),
                      200*(f[..., 1]-f[..., 2])), axis=-1) * 327.68).astype('f4')


def from_lab(lab):
    lab = np.asarray(lab, 'f8') / 327.68
    fy = (lab[..., 0] + 16) / 116
    f = np.stack((fy + lab[..., 1]/500, fy, fy - lab[..., 2]/200), axis=-1)
    d = 6 / 29
    xyz = np.where(f > d, f**3, (f - 4/29)*3*d*d) * WHITE
    return (xyz @ np.linalg.inv(RGB_D50).T).astype('f4')


def run(binary, folder, lab, radius=2., threshold=13, hue='default'):
    src, dst = folder / 'input.lab', folder / 'output.lab'
    np.asarray(lab, '<f4').tofile(src)
    h, w = lab.shape[:2]
    subprocess.run([str(binary.resolve()), str(src), str(dst), str(w), str(h),
                    str(radius), str(threshold), hue], check=True)
    result = np.fromfile(dst, '<f4').reshape(lab.shape)
    assert np.array_equal(result[..., 0], lab[..., 0]), 'L must be bit-preserved'
    assert np.isfinite(result).all(), 'nonfinite reference result'
    return result


def load_lamp():
    spec = importlib.util.spec_from_file_location('lamp', Path(__file__).with_name('analyze-lampshade-capture.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    ap.add_argument('--token', required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    binary = build(args.output / ('rawtherapee-' + REVISION))
    lamp = load_lamp()
    controls = []
    for colour, rgb in {'pink': (.8,.2,.65), 'red': (.8,.1,.08), 'blue': (.06,.12,.8),
                        'green': (.1,.8,.12), 'cyan': (.08,.6,.7)}.items():
        for width in (1,2,4,8,16,32):
            image = np.full((259,257,3), .05, 'f4')
            left = (257-width)//2
            image[:,left:left+width] = rgb
            lab = to_lab(image)
            assert np.max(np.abs(from_lab(lab)-image)) < 2e-6
            output = run(binary,args.output,lab)
            old = np.linalg.norm(lab[24:-24,left:left+width,1:], axis=-1)
            new = np.linalg.norm(output[24:-24,left:left+width,1:], axis=-1)
            controls.append({'colour':colour,'width':width,
                             'Lab_chroma_retention_mean':float((new/old).mean())})
    image, _ = lamp.stage(args.directory,args.token,'D4-CCM')
    # Full image: global variance is part of the reference, not a crop statistic.
    lab = to_lab(image[...,:3])
    roi = (1300,1750,2000,2380)
    x0,y0,x1,y1 = roi
    baseline = image[::-1][y0:y1,x0:x1,:3].astype('f4')
    columns, rows = lamp.edge_coordinates(baseline)
    results = []
    for radius, threshold, hue in ((2.,13,'default'), (2.,33,'default'),
                                    (2.,66,'default'), (2.,13,'neutral')):
        output = run(binary,args.output,lab,radius,threshold,hue)
        crop = from_lab(output[::-1][y0:y1,x0:x1])
        label = f'r{radius}-t{threshold}-{hue}'
        np.save(args.output/(label+'.npy'),crop)
        Image.fromarray(np.uint8(np.clip(lamp.display(crop),0,1)*255)).save(args.output/(label+'.png'))
        results.append({'label':label,'edge':lamp.edge_metric(crop,columns,rows),
                        'changed_ab_fraction':float(np.mean(np.any(output[...,1:]!=lab[...,1:],axis=-1))),
                        'bt2020_Y_abs_error_roi_max':float(np.abs((crop-baseline)@lamp.LUMA).max())})
        print(label,{k:v for k,v in results[-1].items() if k!='edge'},
              'edge_sum',results[-1]['edge']['magenta_positive_sum'],flush=True)
        del output
    report = {'revision':REVISION,'scope':__doc__,
              'contract':'Original scalar >=.6 Gaussian, complete FlatCurve including default hue curve, '
                         'full-image double chromave and immutable neighbourhood Lab correction. '
                         'Adapters provide array storage; libm atan2 replaces SLEEF. '
                         'Not RT application/ICC pipeline or device bit parity.',
              'roi':roi,'baseline':lamp.edge_metric(baseline,columns,rows),
              'noiseless_controls':controls,'real_lamp':results}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    for row in controls:
        if row['width']==2:
            print('clean 2px',row,flush=True)
    print('saved',args.output/'report.json')


if __name__=='__main__':
    main()

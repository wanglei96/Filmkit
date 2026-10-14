import subprocess, shutil, sys, glob, xml.etree.ElementTree as ET, os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ANNEX='app/src/main/java/com/filmkit/app/heif/HevcAnnexB.kt'
BAK='/tmp/HevcAnnexB.kt.bak'

MUTS = [
 ("M1 lengthSizeMinusOne 写死 0（声明与实际打包不一致）", ANNEX,
  "((if (temporalIdNested) 1 else 0) shl 2) or (lengthSizeMinusOne and 3),",
  "((if (temporalIdNested) 1 else 0) shl 2) or 0,"),
 ("M2 bitDepthChromaMinus8 少读一位（不消费该 ue(v)）", ANNEX,
  "        val bdl = br.ue()\n        val bdc = br.ue()",
  "        val bdl = br.ue()\n        val bdc = if (true) 0 else br.ue()"),
 ("M3 scaling_list_data 的 coefNum 算错（跳过长度少一半）", ANNEX,
  "val coefNum = minOf(64, 1 shl (4 + (sizeId shl 1)))",
  "val coefNum = minOf(64, 1 shl (4 + sizeId))"),
]

def run():
    p = subprocess.run(['./gradlew',':app:testDebugUnitTest','--console=plain',
                        '--tests','com.filmkit.app.heif.*'],
                       capture_output=True, text=True,
                       env={**os.environ,'JAVA_HOME':'/opt/homebrew/opt/openjdk@23'})
    fails=[]
    for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
        r=ET.parse(f).getroot()
        for tc in r.iter('testcase'):
            if list(tc.iter('failure'))+list(tc.iter('error')):
                fails.append(tc.get('classname','').split('.')[-1]+" > "+tc.get('name'))
    return p.returncode, fails

shutil.copy(ANNEX, BAK)
try:
    for name, path, old, new in MUTS:
        s=open(path,encoding='utf-8').read()
        assert old in s, f"变异锚点找不到：{name}"
        open(path,'w',encoding='utf-8').write(s.replace(old,new,1))
        rc, fails = run()
        print(f"\n### {name}\n  exit={rc}  变红用例数={len(fails)}")
        for x in fails[:8]: print("    RED:", x)
        if len(fails)>8: print(f"    ... 另 {len(fails)-8} 条")
        shutil.copy(BAK, path)
finally:
    shutil.copy(BAK, ANNEX)
print("\n已恢复原实现")

from pathlib import Path
import re,json
repo=Path(__file__).resolve().parents[2]
def shader(relative,stops=None):
 s=(repo/relative).read_text(encoding='utf8')
 s=s[s.index('new CharSequence[]{')+len('new CharSequence[]{'):]
 s=s[:s.index('});')]
 lines=[]
 for line in s.splitlines():
  parts=re.findall(r'"(?:[^"\\]|\\.)*"',line)
  if not parts:continue
  if '+ stops +' in line:
   assert stops is not None and len(parts)==2
   lines.append(json.loads(parts[0])+str(stops)+json.loads(parts[1]))
  else:
   assert len(parts)==1,line
   lines.append(json.loads(parts[0]))
 return '\n'.join(lines)
base='app/src/main/java/com/winlator/cmod/renderer/'
out={key:shader(base+file,stops)for key,file,stops in [('vertex','material/ScreenMaterial.java',None),('easu','effects/FSREasuEffect.java',None),('rcas','effects/FSREffect.java',.8)]}
Path(__file__).with_name('fsr-shaders.json').write_text(json.dumps(out),encoding='utf8')
print('Exported literal shader sources; RCAS stops=0.8')

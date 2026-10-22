import subprocess,sys
S=open(''+__import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)),'..')+'/android/app/src/main/assets/run_project.sh').read()
def parse(text):
    lang="";steps=[];flags=[];inStep=False;buf=[]
    for l in text.split("\n"):
        if l.startswith("RUNNER:LANG "): lang=l[12:].strip()
        elif l.startswith("RUNNER:STEP "): inStep=True;buf=[]
        elif l.startswith("RUNNER:EXIT "):
            p=l[12:].strip().split(" "); steps.append((p[0],int(p[1]) if len(p)>1 and p[1].lstrip('-').isdigit() else -1,"\n".join(buf).strip())); inStep=False
        elif l.startswith("RUNNER:DONE"): pass
        elif l.startswith("RUNNER:"): flags.append(l[7:].strip())
        elif inStep: buf.append(l)
    last={}
    for st in steps:
        last.pop(st[0],None); last[st[0]]=st
    return lang,list(last.values()),flags
def env_problem(lang,flags):
    if any(f.startswith("NO_SOURCE") for f in flags): return "no source"
    if any(f.startswith("COPY_FAILED") for f in flags): return "copy failed"
    if any(f.startswith("MISSING") for f in flags): return "missing tool: "+[f for f in flags if f.startswith("MISSING")][0]
    if lang=="static": return "static website -> use site_test"
    if lang=="unknown": return "unknown language"
def problems(steps,flags):
    out=[]
    for n,c,o in steps:
        if n=="install" and c!=0: out.append("install failed")
        elif n=="build" and c!=0: out.append("build failed")
        elif n=="tests" and c!=0: out.append("tests failed")
        elif n=="run" and c not in (0,124): out.append("crashed")
    for f in flags:
        if f.startswith("INSTALLED ") and "-not-listed-in-" in f: out.append("package not listed: "+f)
    if any(f.startswith("NO_ENTRY") for f in flags): out.append("no entry file")
    return out
for p in sys.argv[1:]:
    r=subprocess.run(["bash","-c",S,"runner","/tmp/rt/"+p,"install","3"],capture_output=True,text=True).stdout
    lang,steps,flags=parse(r); e=env_problem(lang,flags)
    print(f"{p:12} lang={lang:8} ->", ("ENV: "+e) if e else (problems(steps,flags) or "OK (nothing failed)"))

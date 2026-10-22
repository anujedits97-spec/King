// Runs the STEP_JS string shipped in SiteKit.kt against a fake DOM. Usage: node tests/sitekit_steps_test.mjs
import { createRequire } from 'module';const require=createRequire(import.meta.url);const __dirname=require('path').dirname(new URL(import.meta.url).pathname);const fs=require('fs');
const src=fs.readFileSync(require('path').join(__dirname,'..','android/app/src/main/java/dev/kairo/myra/brain/mark/SiteKit.kt'),'utf8');
const m=src.match(/STEP_JS = """([\s\S]*?)"""/); if(!m){console.log('STEP_JS not found');process.exit(1)}
const code=m[1];
class Ev{constructor(t,o){this.t=t}}
function mk(o){return Object.assign({clicked:false,value:'',innerText:'',offsetParent:{},focus(){},dispatchEvent(){this.ev=(this.ev||0)+1},click(){this.clicked=true}},o)}
const els={'#btn':mk({}),'#name':mk({}),'#list':mk({innerText:'Milk and eggs'}),'#hid':mk({offsetParent:null})};
global.document={querySelector:s=>els[s]||null}; global.Event=Ev;
const run=st=>eval(code.replace('__STEP__',JSON.stringify(st)));
const cases=[
 [{do:'click',sel:'#btn'},'ok'],[{do:'click',sel:'#nope'},'no element #nope'],
 [{do:'type',sel:'#name',text:'Anuj'},'ok'],[{do:'select',sel:'#name',text:'x'},'ok'],
 [{do:'expect_text',sel:'#list',text:'Milk'},'ok'],[{do:'expect_text',sel:'#list',text:'Bread'},'text not found in #list'],
 [{do:'expect_exists',sel:'#btn'},'ok'],[{do:'expect_exists',sel:'#zzz'},'no element #zzz'],
 [{do:'expect_hidden',sel:'#hid'},'ok'],[{do:'expect_hidden',sel:'#btn'},'#btn is still visible'],
 [{do:'fly',sel:'#btn'},'unknown step fly'],[{do:'click',sel:'#x"quote'},'no element #x"quote']];
let bad=0;for(const [st,want] of cases){let got;try{got=run(st)}catch(e){got='THROW '+e.message}
 const ok=got===want;if(!ok)bad++;console.log(ok?'PASS':'FAIL',JSON.stringify(st),'->',got)}
console.log('side effects:',els['#btn'].clicked, els['#name'].value, els['#name'].ev);
console.log(bad?bad+' FAILED':'all step cases passed');

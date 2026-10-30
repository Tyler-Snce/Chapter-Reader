package com.chapterreader.app

/** JavaScript run inside the in-app browser. */
object Scripts {

    private val CORE = """
(function(){
if(window.__cr)return;
function clean(t){return (t||'').replace(/\s+/g,' ').trim();}
function extract(doc){
  var groups=new Map();
  doc.querySelectorAll('p').forEach(function(p){
    var par=p.parentElement;if(!par)return;
    var t=clean(p.textContent);if(!t)return;
    var g=groups.get(par);if(!g){g={el:par,len:0};groups.set(par,g);}
    g.len+=t.length;
  });
  var best=null;
  groups.forEach(function(g){if(!best||g.len>best.len)best=g;});
  if(best&&best.len>300){
    var els=[],texts=[];
    Array.prototype.forEach.call(best.el.children,function(c){
      if(c.tagName==='P'){var t=clean(c.textContent);if(t){els.push(c);texts.push(t);}}
    });
    return {els:els,texts:texts};
  }
  var brGroups=new Map();
  doc.querySelectorAll('br').forEach(function(br){var par=br.parentElement;if(par)brGroups.set(par,(brGroups.get(par)||0)+1);});
  var bestBr=null,n=0;
  brGroups.forEach(function(c,el){if(c>n){n=c;bestBr=el;}});
  if(bestBr&&n>=5){
    var clone=bestBr.cloneNode(true);
    clone.querySelectorAll('script,style,noscript').forEach(function(x){x.remove();});
    clone.querySelectorAll('br').forEach(function(br){br.replaceWith('\n');});
    return {els:null,texts:clone.textContent.split('\n').map(clean).filter(Boolean)};
  }
  return null;
}
function heading(doc){
  var hs=doc.querySelectorAll('h1,h2,h3,h4');
  var list=[];for(var i=0;i<hs.length;i++){var t=clean(hs[i].textContent);if(t.length<150&&!/comment/i.test(t))list.push(t);}
  for(var j=0;j<list.length;j++)if(/\b(chapter|ch\.?|episode|part)\s*\d/i.test(list[j]))return list[j];
  for(var k=0;k<list.length;k++)if(/chap|ch\.?\s*\d|episode|part\s*\d/i.test(list[k]))return list[k];
  return '';
}
function links(doc,base){
  var res={next:null,prev:null},best={next:0,prev:0};
  function consider(kind,href,sc){
    if(sc<=best[kind])return;
    try{var u=new URL(href,base).href;if(u.split('#')[0]===base.split('#')[0])return;if(u.indexOf('http')!==0)return;res[kind]=u;best[kind]=sc;}catch(e){}
  }
  doc.querySelectorAll('link[rel][href]').forEach(function(l){
    var rel=(l.getAttribute('rel')||'').toLowerCase();
    if(/\bnext\b/.test(rel))consider('next',l.getAttribute('href'),3);
    if(/\bprev(ious)?\b/.test(rel))consider('prev',l.getAttribute('href'),3);
  });
  doc.querySelectorAll('a[href]').forEach(function(a){
    var href=a.getAttribute('href');
    if(!href||href.charAt(0)==='#'||/^javascript:/i.test(href))return;
    var cls=(a.getAttribute('class')||'').toLowerCase();
    if(/\bdisabled\b/.test(cls)||a.getAttribute('aria-disabled')==='true')return;
    var t=(clean(a.textContent)+' '+(a.getAttribute('title')||'')+' '+(a.getAttribute('aria-label')||'')+' '+(a.id||'')+' '+cls).toLowerCase();
    var rel=(a.getAttribute('rel')||'').toLowerCase();
    var chap=/chap|\bch\b/.test(t)?1:0;
    var sn=(/\bnext\b/.test(rel)?3:0)+(/next/.test(t)?2:0);
    if(sn)consider('next',href,sn+chap);
    var sp=(/\bprev/.test(rel)?3:0)+(/prev/.test(t)?2:0);
    if(sp)consider('prev',href,sp+chap);
  });
  return res;
}
window.__cr={extract:extract,heading:heading,links:links};
var st=document.createElement('style');
st.textContent='.cr-cur{background:rgba(255,200,0,.3)!important;border-radius:4px;box-shadow:0 0 0 4px rgba(255,200,0,.3)}';
(document.head||document.documentElement).appendChild(st);
document.addEventListener('click',function(e){
  if(!window.CRBridge||e.target.closest('a'))return;
  var p=e.target.closest('p');if(!p)return;
  var ex=extract(document);if(!ex||!ex.els)return;
  var i=ex.els.indexOf(p);if(i>=0)CRBridge.tap(i);
},true);
})();
"""

    /** Returns the chapter on the current page as an object. */
    val EXTRACT = CORE + """
(function(){
  var ex=window.__cr.extract(document);
  if(!ex||!ex.texts.length)return {ok:false};
  var l=window.__cr.links(document,location.href);
  var start=0;
  if(ex.els){for(var i=0;i<ex.els.length;i++){if(ex.els[i].getBoundingClientRect().bottom>60){start=i;break;}}}
  return {ok:true,url:location.href,title:document.title,heading:window.__cr.heading(document),paras:ex.texts,next:l.next,prev:l.prev,start:start};
})()
"""

    /** Prepares the page so tapping a paragraph starts reading there. */
    val INSTALL = CORE + "true"

    /** Highlights paragraph [index] and scrolls it into view. */
    fun highlight(index: Int): String = CORE + """
(function(i){
  var ex=window.__cr.extract(document);
  if(!ex||!ex.els)return;
  var old=document.querySelectorAll('.cr-cur');
  for(var k=0;k<old.length;k++)old[k].classList.remove('cr-cur');
  var el=ex.els[i];if(!el)return;
  el.classList.add('cr-cur');
  el.scrollIntoView({block:'center',behavior:'smooth'});
})(""" + index + ")"
}

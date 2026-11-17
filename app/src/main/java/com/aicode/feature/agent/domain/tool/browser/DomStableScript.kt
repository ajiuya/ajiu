package com.aicode.feature.agent.domain.tool.browser

/** CSS compositor animation does not mutate the DOM and must not extend the quiet period. */
internal object DomStableScript {
    fun install(quotedId: String, quietMs: Long, deadlineMs: Long): String = """
        (function(){
            var id=$quotedId,quietMs=$quietMs,deadlineMs=$deadlineMs;
            var registry=window.__bicodeDomWaits||(window.__bicodeDomWaits=Object.create(null));
            var observer=null,quiet=null,deadline=null,done=false;
            function finish(value,error){
                if(done)return;done=true;
                if(observer)observer.disconnect();
                clearTimeout(quiet);clearTimeout(deadline);
                window.removeEventListener('pagehide',onHide);
                delete registry[id];
                if(error){__browserBridge__.reject(id,String(error));}
                else{__browserBridge__.resolve(id,JSON.stringify(value));}
            }
            function onHide(){finish(false);}
            function changed(){clearTimeout(quiet);quiet=setTimeout(function(){finish(true);},quietMs);}
            registry[id]=function(){finish(false);};
            try{
                window.addEventListener('pagehide',onHide);
                observer=new MutationObserver(changed);
                observer.observe(document,{subtree:true,childList:true,characterData:true,attributes:true});
                deadline=setTimeout(function(){finish(false);},deadlineMs);
                changed();
            }catch(error){finish(false,error);}
        })();
    """.trimIndent()

    fun cleanup(quotedId: String): String =
        "(function(){var r=window.__bicodeDomWaits;if(r&&r[$quotedId])r[$quotedId]();})();"
}

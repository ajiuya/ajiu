package com.aicode.feature.player.presentation

internal object PlayerHandoffScript {
    fun capture(quotedUrl: String): String = """
        (function(){try{
          if(location.href!==$quotedUrl)return false;
          var p=window.AjiuPlayer;
          if(!p||typeof p.snapshot!=='function')return false;
          var h=p.snapshot();
          if(!h||!Array.isArray(h.items))return false;
          sessionStorage.setItem('ajiu.native.handoff',JSON.stringify(h));
          return true;
        }catch(e){return false}})()
    """.trimIndent()
}

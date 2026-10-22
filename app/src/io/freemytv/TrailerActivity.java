package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A trailer played in YouTube's own embedded player, inside this app's task: Back returns to the title's page (the
 * YouTube app keeps its own history, so Back there only goes to its home). OK pauses, left and right skip 10 seconds.
 * The page may only load YouTube; anything else is refused. A trailer that can't be embedded (the studio turned it
 * off) offers to open the YouTube app instead.
 */
public class TrailerActivity extends Activity {
    static final String EXTRA_KEY = "key", EXTRA_TITLE = "title";
    private static final String PAGE = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style></head>"
            + "<body><div id='p'></div><script src='https://www.youtube.com/iframe_api'></script><script>"
            + "var player;"
            + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:'%KEY%',"
            + "playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,fs:0,modestbranding:1,iv_load_policy:3,disablekb:1},"
            + "events:{onReady:function(e){e.target.playVideo();console.log('ready')},"
            + "onStateChange:function(e){console.log('state '+e.data)},onError:function(e){console.log('error '+e.data)}}})}"
            + "function toggle(){if(!player)return;player.getPlayerState()==1?player.pauseVideo():player.playVideo()}"
            + "function skip(s){if(!player)return;player.seekTo(player.getCurrentTime()+s,true)}"
            + "</script></body></html>";

    private WebView web;
    private TextView hint;
    private String key, title;
    private boolean failed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        key = getIntent().getStringExtra(EXTRA_KEY);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (key == null || !key.matches("[A-Za-z0-9_-]{6,20}")) {     // the id goes into the page: only what a YouTube id can be
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setFocusable(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;    // the page never navigates: the player is embedded in it
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                String text = m.message();
                AppLog.w("trailer " + text);
                if (text.startsWith("state ")) {
                    if (text.equals("state 0")) finish();      // ended: back to the title
                    else if (text.equals("state 1")) hint(null);
                } else if (text.startsWith("error ")) {
                    failed = true;
                    hint(Ui.tr("Este trailer não pode ser exibido aqui. OK abre no YouTube · Voltar fecha",
                            "This trailer can't be played here. OK opens it in YouTube · Back closes"));
                }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hint = new TextView(this);
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(22);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(40, 20, 40, 20);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 80;
        root.addView(hint, lp);
        setContentView(root);
        // An https address as the page's origin: YouTube refuses embeds that come with no referrer.
        web.loadDataWithBaseURL("https://io.freemytv.app/", PAGE.replace("%KEY%", key), "text/html", "utf-8", null);
        hint(Ui.tr("OK pausa · ◀ ▶ pula 10 s · Voltar fecha", "OK pauses · ◀ ▶ skip 10 s · Back closes"));
        hint.postDelayed(() -> { if (!failed) hint(null); }, 4000);
    }

    private void hint(String text) {
        if (hint == null) return;
        hint.setText(text == null ? "" : text);
        hint.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (web == null) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (failed) {
                        openInYouTube();
                    } else {
                        web.evaluateJavascript("toggle()", null);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    web.evaluateJavascript("skip(-10)", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    web.evaluateJavascript("skip(10)", null);
                    return true;
                default:
                    break;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        return super.dispatchKeyEvent(e);
    }

    /** The studio doesn't allow embedding this one: the YouTube app (where Back goes to YouTube's home). */
    private void openInYouTube() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage("com.google.android.youtube.tv")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            AppLog.w("couldn't open YouTube: " + e.getMessage());
        }
        finish();
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}

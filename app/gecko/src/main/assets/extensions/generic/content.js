// This is a leftover from the removal of the abandoned "text selection" feature.
// I'll leave it here for now as a framework for future extension-based features.

console.log("TV Bro generic content extension loaded");

window.addEventListener('load', function () {
    //document.body.style.userSelect = "none";
    console.log("window.load executed");
});

const communicatePort = browser.runtime.connectNative("tvbro_content");

communicatePort.onMessage.addListener(message => {
    if (message.nativeVideoAvailable && window.top === window &&
        location.protocol === 'https:' && ['www.cycani.org', 'cycani.org'].includes(location.hostname)) {
        installNativeVideoButton();
    }
    if (Number.isFinite(message.nativeVideoPositionMs)) {
        pendingNativePosition = message.nativeVideoPositionMs / 1000;
    }
    if (message.nativeVideoFailed && nativeVideo) {
        nativeVideo.play().catch(() => {});
        nativeVideo = null;
    }
});

let nativeVideo = null;
let pendingNativePosition = null;
function installNativeVideoButton() {
    const launch = event => {
        if (!event.isTrusted) return;
        const video = document.querySelector('video');
        if (!video || !video.currentSrc || !Number.isFinite(video.currentTime)) return;
        const url = new URL(video.currentSrc);
        if (url.protocol !== 'https:' || !url.hostname.endsWith('.cycstream.com') ||
            !url.pathname.toLowerCase().endsWith('.mp4')) return;
        event.preventDefault();
        event.stopPropagation();
        nativeVideo = video;
        video.pause();
        communicatePort.postMessage({type:'nativeVideo', url:video.currentSrc,
            positionMs:Math.round(video.currentTime * 1000)});
    };
    const install = () => {
        const video = document.querySelector('video');
        let launcher = document.querySelector('.tvbro-native-video-launcher');
        if (!video) {
            launcher?.remove();
            return;
        }
        if (!launcher) {
            launcher = document.createElement('button');
            launcher.className = 'tvbro-native-video-launcher';
            launcher.type = 'button';
            launcher.textContent = '原生播放';
            launcher.setAttribute('aria-label', '使用原生播放器播放此集');
            launcher.title = '原画质播放，返回键回到网页';
            launcher.style.cssText = 'position:fixed;top:72px;right:24px;z-index:1000;width:132px;height:42px;border:1px solid #fff8;border-radius:8px;background:#282828;color:white;font-size:16px;cursor:pointer';
            launcher.addEventListener('click', launch);
            document.body.append(launcher);
        }
        if (pendingNativePosition !== null && video && video.readyState >= 1) {
            video.pause();
            video.currentTime = Math.min(pendingNativePosition, video.duration || pendingNativePosition);
            pendingNativePosition = null;
            communicatePort.postMessage({type:'nativeVideoPositionApplied'});
        }
        const controls = document.querySelector('.art-controls-right');
        if (!controls || controls.querySelector('.tvbro-native-video')) return;
        const button = document.createElement('button');
        button.className = 'art-control tvbro-native-video';
        button.textContent = '原生播放';
        button.title = '使用原画质播放，返回键回到网页';
        button.style.cssText = 'width:88px;color:white;background:transparent;border:0;font-size:14px;cursor:pointer';
        button.addEventListener('click', launch);
        controls.prepend(button);
    };
    // SPA navigation can replace the player; no per-frame work is needed.
    install();
    const timer = setInterval(install, 2000);
    window.addEventListener('pagehide', () => clearInterval(timer), {once:true});
}

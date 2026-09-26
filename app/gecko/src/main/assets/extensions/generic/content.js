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
    const install = () => {
        const video = document.querySelector('video');
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
        button.addEventListener('click', event => {
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
        });
        controls.prepend(button);
    };
    // SPA navigation can replace the player; no per-frame work is needed.
    install();
    const timer = setInterval(install, 2000);
    window.addEventListener('pagehide', () => clearInterval(timer), {once:true});
}

package com.tvlive.app.player;

import android.os.Handler;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DecoderAudioRenderer;
import com.google.android.exoplayer2.decoder.CryptoConfig;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

/**
 * MP2（MPEG-1 Layer II）音频渲染器。
 *
 * <p>系统 MediaCodec 没有 MP2 解码器（AOSP 的 OMX.google.mp3.decoder 只支持 Layer III），
 * 因此这里通过 {@link Mp2AudioDecoder} 做纯 Java 软解，再交给 {@link AudioSink} 播放。
 * 只接管 {@code audio/mpeg-L2}，其余音频格式继续由系统 MediaCodec 渲染器处理。
 */
public final class Mp2AudioRenderer extends DecoderAudioRenderer<Mp2AudioDecoder> {

    private Format inputFormat;
    private Mp2AudioDecoder decoderRef;
    private boolean loggedFirstPcm;

    public Mp2AudioRenderer(Handler eventHandler, AudioRendererEventListener eventListener,
                            AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "Mp2AudioRenderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        String mimeType = format.sampleMimeType;
        if (!MimeTypes.AUDIO_MPEG_L2.equals(mimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        // Android 4.4（API < 21）：系统有 OMX.google.mstmp3.decoder（audio/mpeg-L2），
        // 用系统解码器（快，CPU 低，不卡顿），避免 JavaMP3 纯 Java 解码在老旧 CPU 上性能不足。
        // Android 5+：系统解码器可能输出异常（如 Android 17 莎莎声），用 JavaMP3 软解（已验证正常）。
        if (android.os.Build.VERSION.SDK_INT < 21) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        int channels = format.channelCount > 0 ? format.channelCount : 2;
        int sampleRate = format.sampleRate > 0 ? format.sampleRate : 48000;
        Format pcmFormat = Util.getPcmFormat(C.ENCODING_PCM_16BIT, channels, sampleRate);
        if (!sinkSupportsFormat(pcmFormat)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected Mp2AudioDecoder createDecoder(Format format, CryptoConfig cryptoConfig)
            throws DecoderException {
        inputFormat = format;
        int channels = format.channelCount > 0 ? format.channelCount : 2;
        int sampleRate = format.sampleRate > 0 ? format.sampleRate : 48000;
        android.util.Log.i("Mp2AudioRenderer", "MP2 软解器已创建: " + sampleRate + "Hz " + channels + "声道");
        decoderRef = new Mp2AudioDecoder(sampleRate, channels);
        return decoderRef;
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer inputBuffer) {
        super.onQueueInputBuffer(inputBuffer);
        if (!loggedFirstPcm && decoderRef != null && decoderRef.getDecodedFrameCount() > 0) {
            loggedFirstPcm = true;
            android.util.Log.i("Mp2AudioRenderer", "MP2 软解输出 PCM: 已解码 " + decoderRef.getDecodedFrameCount()
                    + " 帧, 失败 " + decoderRef.getFailedFrameCount()
                    + " 帧, " + decoderRef.getSampleRate() + "Hz "
                    + decoderRef.getChannelCount() + "声道");
        }
    }

    @Override
    protected Format getOutputFormat(Mp2AudioDecoder decoder) {
        int channels = decoder.getChannelCount();
        int sampleRate = decoder.getSampleRate();
        if (channels <= 0 && inputFormat != null) {
            channels = inputFormat.channelCount;
        }
        if (sampleRate <= 0 && inputFormat != null) {
            sampleRate = inputFormat.sampleRate;
        }
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(channels > 0 ? channels : 2)
                .setSampleRate(sampleRate > 0 ? sampleRate : 48000)
                .build();
    }
}

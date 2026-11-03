package com.tvlive.app.player;

import android.util.Log;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DecoderAudioRenderer;
import com.google.android.exoplayer2.decoder.CryptoConfig;
import com.google.android.exoplayer2.decoder.DecoderException;

import java.util.List;

/**
 * MPEG-1/2 Audio Layer II (MP2) 渲染器。
 * <p>
 * 用纯 Java {@link MpegAudioDecoder} 解码 MP2 音频，无需系统 MediaCodec 支持。
 * 用于 Android 8+ 缺少 MP2 MediaCodec 解码器时的兜底。
 */
final class MpegAudioTrackRenderer extends DecoderAudioRenderer<MpegAudioDecoder> {

    private static final String TAG = "MpegAudioTrackRenderer";

    MpegAudioTrackRenderer(android.os.Handler eventHandler,
            AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        String mime = format.sampleMimeType;
        if ("audio/mpeg".equals(mime) || "audio/mpeg-L2".equals(mime)
                || "audio/mp2".equals(mime) || "audio/mpeg-l2".equals(mime)) {
            return RendererCapabilities.FORMAT_HANDLED;
        }
        return RendererCapabilities.FORMAT_UNSUPPORTED_TYPE;
    }

    @Override
    protected MpegAudioDecoder createDecoder(Format format, CryptoConfig cryptoConfig)
            throws DecoderException {
        try {
            return new MpegAudioDecoder();
        } catch (Throwable t) {
            throw new DecoderException("MP2 解码器创建失败", t);
        }
    }

    @Override
    protected Format getOutputFormat(MpegAudioDecoder decoder) {
        return decoder.getOutputFormat();
    }

    @Override
    public String getName() {
        return "MpegAudioTrackRenderer";
    }
}

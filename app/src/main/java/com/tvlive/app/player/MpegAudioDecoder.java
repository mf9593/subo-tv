package com.tvlive.app.player;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.Decoder;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderOutputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 纯 Java MPEG-1/2 Audio Layer II (MP2) 解码器。
 * <p>
 * 实现 Decoder 接口，输出 16-bit PCM 交错样本。
 * 用于 Android 8+ 缺少 MP2 MediaCodec 解码器时的兜底。
 */
final class MpegAudioDecoder
        implements Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private final Queue<DecoderInputBuffer> availableInputs = new ArrayDeque<DecoderInputBuffer>();
    private final Queue<SimpleDecoderOutputBuffer> availableOutputs = new ArrayDeque<SimpleDecoderOutputBuffer>();
    private final Queue<SimpleDecoderOutputBuffer> outputs = new ArrayDeque<SimpleDecoderOutputBuffer>();
    private Format format;

    private static final int MAX_INPUT_BUFFERS = 10;
    private static final int MAX_OUTPUT_BUFFERS = 10;

    @Override
    public String getName() {
        return "MpegAudioDecoder";
    }

    @Override
    public DecoderInputBuffer dequeueInputBuffer() {
        DecoderInputBuffer buf = availableInputs.poll();
        if (buf == null && availableInputs.size() + countActiveInputs() < MAX_INPUT_BUFFERS) {
            buf = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
        }
        if (buf != null) buf.clear();
        return buf;
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
        if (inputBuffer.isEndOfStream()) {
            // 结束标记：生成结束输出
            SimpleDecoderOutputBuffer eos = getAvailableOutput();
            eos.timeUs = inputBuffer.timeUs;
            eos.addFlag(com.google.android.exoplayer2.C.BUFFER_FLAG_END_OF_STREAM);
            outputs.add(eos);
            return;
        }
        try {
            byte[] data = new byte[inputBuffer.data.remaining()];
            inputBuffer.data.get(data);
            // 解码数据（累积，可能跨帧）
            decodeData(data, inputBuffer.timeUs);
        } catch (Throwable t) {
            // 解码失败：忽略该帧
        }
    }

    @Override
    public SimpleDecoderOutputBuffer dequeueOutputBuffer() {
        return outputs.poll();
    }

    @Override
    public void flush() {
        availableInputs.clear();
        availableOutputs.clear();
        outputs.clear();
        accLen = 0;
        nextTimeUs = -1;
        // 重置合成历史缓冲
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 512; i++) synthU[ch][i] = 0;
        }
    }

    @Override
    public void release() {
        availableInputs.clear();
        availableOutputs.clear();
        outputs.clear();
        accLen = 0;
        nextTimeUs = -1;
        // 重置合成历史缓冲
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 512; i++) synthU[ch][i] = 0;
        }
    }

    /** 输出格式（PCM 16bit） */
    Format getOutputFormat() {
        return format;
    }

    private int activeInputs;
    private int countActiveInputs() {
        return activeInputs;
    }

    private SimpleDecoderOutputBuffer getAvailableOutput() {
        SimpleDecoderOutputBuffer out = availableOutputs.poll();
        if (out == null) {
            out = new SimpleDecoderOutputBuffer(new DecoderOutputBuffer.Owner<SimpleDecoderOutputBuffer>() {
                @Override
                public void releaseOutputBuffer(SimpleDecoderOutputBuffer outputBuffer) {
                    availableOutputs.add(outputBuffer);
                }
            });
        }
        return out;
    }

    /** 累积数据缓冲 */
    private byte[] accData = new byte[4096];
    private int accLen = 0;
    /** 下一帧输出时间戳（微秒），避免 inputBuffer.timeUs 双重递增 */
    private long nextTimeUs = -1;

    private void decodeData(byte[] data, long timeUs) {
        // 累积
        if (accLen + data.length > accData.length) {
            byte[] nd = new byte[Math.max(accData.length * 2, accLen + data.length)];
            System.arraycopy(accData, 0, nd, 0, accLen);
            accData = nd;
        }
        System.arraycopy(data, 0, accData, accLen, data.length);
        accLen += data.length;

        // 循环解码完整帧
        int pos = 0;
        while (pos + 4 <= accLen) {
            int b0 = accData[pos] & 0xFF;
            int b1 = accData[pos + 1] & 0xFF;
            if ((b0 & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) {
                pos++; // 找同步字
                continue;
            }
            FrameInfo fi = parseFrameHeader(accData, pos);
            if (fi == null) {
                pos++;
                continue;
            }
            if (pos + fi.frameLength > accLen) break; // 不完整帧
            // 设置输出格式（第一次解码时）
            if (format == null) {
                format = new Format.Builder()
                        .setSampleMimeType(com.google.android.exoplayer2.util.MimeTypes.AUDIO_RAW)
                        .setSampleRate(fi.sampleRate)
                        .setChannelCount(fi.channels)
                        .setPcmEncoding(com.google.android.exoplayer2.C.ENCODING_PCM_16BIT)
                        .build();
            }
            // MPEG Layer II 每帧 1152 样本
            long frameDurationUs = 1152L * 1000000L / fi.sampleRate;
            // 新批次：如果 inputBuffer.timeUs 明显超过 nextTimeUs，则以它为基准
            if (nextTimeUs < 0 || timeUs > nextTimeUs + frameDurationUs / 2) {
                nextTimeUs = timeUs;
            }
            // 解码该帧
            short[] pcm = decodeFrame(accData, pos, fi);
            if (pcm != null && pcm.length > 0) {
                SimpleDecoderOutputBuffer out = getAvailableOutput();
                out.timeUs = nextTimeUs;
                nextTimeUs += frameDurationUs;
                out.data = java.nio.ByteBuffer.allocateDirect(pcm.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (short s : pcm) out.data.putShort(s);
                out.data.flip();
                outputs.add(out);
            }
            pos += fi.frameLength;
        }
        // 移除已处理的字节
        if (pos > 0) {
            int remain = accLen - pos;
            System.arraycopy(accData, pos, accData, 0, remain);
            accLen = remain;
        }
    }

    /** 检查字节是否以 MPEG 音频帧头开头 */
    static boolean isMpegHeader(ByteBuffer data) {
        if (data == null || data.remaining() < 4) return false;
        int b0 = data.get(data.position()) & 0xFF;
        int b1 = data.get(data.position() + 1) & 0xFF;
        return (b0 & 0xFF) == 0xFF && (b1 & 0xE0) == 0xE0;
    }

    static boolean isMpegHeader(String mime) {
        return false;
    }

    /** 解析 MPEG 帧头（Layer II） */
    private static FrameInfo parseFrameHeader(byte[] data, int pos) {
        if (pos + 4 > data.length) return null;
        int b0 = data[pos] & 0xFF;
        int b1 = data[pos + 1] & 0xFF;
        int b2 = data[pos + 2] & 0xFF;
        int b3 = data[pos + 3] & 0xFF;
        if ((b0 & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) return null;
        int version = (b1 >> 3) & 0x3;
        int layer = (b1 >> 1) & 0x3;
        if (layer != 2) return null; // 仅 Layer II
        if (version == 1) return null; // 保留
        int bitrateIdx = (b2 >> 4) & 0xF;
        int sampleIdx = (b2 >> 2) & 0x3;
        if (bitrateIdx == 0 || bitrateIdx == 15 || sampleIdx == 3) return null;
        int channelMode = (b3 >> 6) & 0x3;
        int padding = (b2 >> 1) & 0x1;
        int sampleRate;
        int bitrate;
        if (version == 3) {
            sampleRate = new int[]{44100, 48000, 32000}[sampleIdx];
            bitrate = new int[]{0,32,48,56,64,80,96,112,128,160,192,224,256,320,384}[bitrateIdx];
        } else {
            sampleRate = new int[]{22050, 24000, 16000}[sampleIdx];
            if (version == 0) sampleRate /= 2;
            bitrate = new int[]{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160}[bitrateIdx];
        }
        int frameLength = 144 * bitrate * 1000 / sampleRate + padding;
        FrameInfo fi = new FrameInfo();
        fi.sampleRate = sampleRate;
        fi.bitrateKbps = bitrate;
        fi.channels = channelMode == 3 ? 1 : 2;
        fi.frameLength = frameLength;
        fi.version = version;
        fi.channelMode = channelMode;
        return fi;
    }

    /** 合成滤波器组状态：历史缓冲（每通道 512 点） */
    private final float[][] synthU = new float[2][512];
    /** 合成矩阵 N[i][k] = cos((16+i)(2k+1)π/64)，i=0..63, k=0..31 */
    private static final double[][] COS = new double[64][32];
    /** 512 点合成窗口表（近似标准 MPEG 窗口） */
    private static final float[] W = new float[512];
    static {
        for (int i = 0; i < 64; i++) {
            for (int k = 0; k < 32; k++) {
                COS[i][k] = Math.cos((16 + i) * (2 * k + 1) * Math.PI / 64);
            }
        }
        for (int n = 0; n < 512; n++) {
            int phase = n & 31;
            W[n] = (float) (Math.sin(Math.PI * (phase + 0.5) / 32) * Math.sin(Math.PI * (n + 0.5) / 512));
        }
    }

    /** 解码一帧 MP2 → PCM（16bit 交错）。每帧 1152 样本/通道。 */
    private short[] decodeFrame(byte[] data, int pos, FrameInfo fi) {
        int nCh = fi.channels;
        boolean mono = fi.channelMode == 3;
        BitReader br = new BitReader(data, pos + 4, pos + fi.frameLength);

        // Layer II 位分配表（数据驱动确定）：sb0-7: 4位, sb8-11: 2位, sb12-31: 0位
        // 适用 MPEG-1 192kbps 48kHz 立体声（CCTV1 实测）
        int[][] allocation = new int[32][2];
        for (int sb = 0; sb < 32; sb++) {
            int ab = sb < 8 ? 4 : (sb < 12 ? 2 : 0);
            for (int ch = 0; ch < nCh; ch++) {
                allocation[sb][ch] = ab > 0 ? br.readBits(ab) : 0;
            }
        }
        // 比例因子：Layer II 每个子带组（3 个）各有比例因子
        int[][][] scalefactor = new int[3][32][2];
        for (int grp = 0; grp < 3; grp++) {
            for (int sb = 0; sb < 32; sb++) {
                for (int ch = 0; ch < nCh; ch++) {
                    if (allocation[sb][ch] != 0) {
                        scalefactor[grp][sb][ch] = br.readBits(6);
                    }
                }
            }
        }
        // 样本：3 个子带组 × 12 样本/子带 × 32 子带 × 通道 = 1152 样本/通道
        int[][][][] samples = new int[3][12][32][2];
        for (int grp = 0; grp < 3; grp++) {
            for (int sb = 0; sb < 32; sb++) {
                for (int ch = 0; ch < nCh; ch++) {
                    int alloc = allocation[sb][ch];
                    if (alloc > 0) {
                        int bits = sampleBits(alloc, fi.version);
                        for (int samp = 0; samp < 12; samp++) {
                            int raw = br.readBits(bits);
                            // 逆量化：有符号 + 应用比例因子（Layer II: 2^(-sf/3)）
                            double sf = Math.pow(2.0, -scalefactor[grp][sb][ch] / 3.0);
                            samples[grp][samp][sb][ch] = (int) Math.round((raw - (1 << (bits - 1))) * sf);
                        }
                    }
                }
            }
        }
        // 合成：标准 ISO 64×32 DCT + 历史缓冲 + 512 点窗口
        short[] out = new short[3 * 12 * 32 * nCh]; // 1152 * nCh
        int outIdx = 0;
        for (int grp = 0; grp < 3; grp++) {
            for (int samp = 0; samp < 12; samp++) {
                int[] inL = new int[32];
                int[] inR = new int[32];
                for (int sb = 0; sb < 32; sb++) {
                    inL[sb] = samples[grp][samp][sb][0];
                    if (!mono) inR[sb] = samples[grp][samp][sb][1];
                }
                if (mono) {
                    synthStandard(inL, allocation, 0, out, outIdx, 1);
                    outIdx += 32;
                } else {
                    synthStandard(inL, allocation, 0, out, outIdx, 2);
                    synthStandard(inR, allocation, 1, out, outIdx + 1, 2);
                    outIdx += 64;
                }
            }
        }
        return out;
    }

    /** 标准 ISO 合成滤波器组：32 子带 → 32 PCM，写入 out[outIdx + stride*j] */
    private void synthStandard(int[] s, int[][] allocation, int ch, short[] out, int outIdx, int stride) {
        // 1. 64×32 DCT 矩阵运算
        double[] V = new double[64];
        for (int i = 0; i < 64; i++) {
            double sum = 0;
            for (int k = 0; k < 32; k++) {
                int bits = sampleBits(allocation[k][ch], 3);
                double norm = (bits > 0) ? s[k] / (double) (1 << (bits - 1)) : 0;
                sum += norm * COS[i][k];
            }
            V[i] = sum;
        }
        // 2. 历史缓冲移位（右移 64）
        float[] U = synthU[ch];
        for (int i = 511; i >= 64; i--) U[i] = U[i - 64];
        for (int i = 0; i < 64; i++) U[i] = (float) V[i];
        // 3. 窗口叠加：out[j] = Σ_{i=0}^{15} U[j+32i] * W[j+32i]
        for (int j = 0; j < 32; j++) {
            double sum = 0;
            for (int i = 0; i < 16; i++) {
                sum += U[j + 32 * i] * W[j + 32 * i];
            }
            out[outIdx + stride * j] = (short) clamp((int) Math.round(sum * 1024.0));
        }
    }

    private static int sampleBits(int alloc, int version) {
        // ISO 11172-3 表 B.3（Layer II 量化位数）
        // alloc: 0→0, 1→2, 2→3, 3→4, 4→5, 5→6, 6→8, 7→10, 8→12, 9→14, 10→16
        if (alloc <= 0) return 0;
        if (alloc == 1) return 2;
        if (alloc == 2) return 3;
        if (alloc == 3) return 4;
        if (alloc == 4) return 5;
        if (alloc == 5) return 6;
        if (alloc == 6) return 8;
        if (alloc == 7) return 10;
        if (alloc == 8) return 12;
        if (alloc == 9) return 14;
        return 16;
    }

    private static int clamp(int v) {
        return Math.max(-32768, Math.min(32767, v));
    }

    static final class FrameInfo {
        int sampleRate;
        int bitrateKbps;
        int channels;
        int frameLength;
        int version;
        int channelMode;
    }

    /** 位流读取器（大端） */
    static final class BitReader {
        private final byte[] data;
        private final int start;
        private final int end;
        private int pos;
        private int bitBuf;
        private int bitCount;

        BitReader(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = Math.min(end, data.length);
            this.pos = start;
        }

        int readBits(int n) {
            if (n <= 0) return 0;
            while (bitCount < n) {
                if (pos < end) {
                    bitBuf = (bitBuf << 8) | (data[pos] & 0xFF);
                    pos++;
                    bitCount += 8;
                } else {
                    bitBuf <<= 8;
                    bitCount += 8;
                }
            }
            bitCount -= n;
            return (bitBuf >> bitCount) & ((1 << n) - 1);
        }
    }
}

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
    private int logFrameCount = 0;

    MpegAudioDecoder() {
        android.util.Log.i("MpegAudioDecoder", "创建 (dct32+apply_window)");
    }

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
        // 重置 FFmpeg 合成历史缓冲
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 1024; i++) SYNTH_BUF[ch][i] = 0;
            SYNTH_OFFSET[ch] = 0;
        }
    }

    @Override
    public void release() {
        availableInputs.clear();
        availableOutputs.clear();
        outputs.clear();
        accLen = 0;
        nextTimeUs = -1;
        // 重置 FFmpeg 合成历史缓冲
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < 1024; i++) SYNTH_BUF[ch][i] = 0;
            SYNTH_OFFSET[ch] = 0;
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

    /** FFmpeg 合成滤波器组历史缓冲（每通道 1024 点，offset ∈ {0,32,...,480}） */
    private final double[][] SYNTH_BUF = new double[2][1024];
    /** 每通道合成偏移（FFmpeg synth_buf_offset，初始 0，每块 -32 & 511） */
    private final int[] SYNTH_OFFSET = new int[2];
    /** 512 点合成窗口表（ff_mpa_enwindow 生成，除以 2^16 归一化，配合 dct32+apply_window） */
    private static final double[] W = new double[512];
    /** dct32 临时缓冲（解码器单线程，复用避免分配） */
    private final double[] DCT_TMP = new double[32];
    /** dct32 输出临时缓冲（解码器单线程，复用避免分配） */
    private final double[] DCT_OUT = new double[32];
    /** apply_window 输出临时缓冲（解码器单线程） */
    private final double[] PCM_TMP = new double[32];

    /** dct32 COS 常数（FFmpeg dct32_template.c，浮点） */
    private static final double COS0_0 = 0.50060299823519630134 / 2;
    private static final double COS0_1 = 0.50547095989754365998 / 2;
    private static final double COS0_2 = 0.51544730992262454697 / 2;
    private static final double COS0_3 = 0.53104259108978417447 / 2;
    private static final double COS0_4 = 0.55310389603444452782 / 2;
    private static final double COS0_5 = 0.58293496820613387367 / 2;
    private static final double COS0_6 = 0.62250412303566481615 / 2;
    private static final double COS0_7 = 0.67480834145500574602 / 2;
    private static final double COS0_8 = 0.74453627100229844977 / 2;
    private static final double COS0_9 = 0.83934964541552703873 / 2;
    private static final double COS0_10 = 0.97256823786196069369 / 2;
    private static final double COS0_11 = 1.16943993343288495515 / 4;
    private static final double COS0_12 = 1.48416461631416627724 / 4;
    private static final double COS0_13 = 2.05778100995341155085 / 8;
    private static final double COS0_14 = 3.40760841846871878570 / 8;
    private static final double COS0_15 = 10.19000812354805681150 / 32;
    private static final double COS1_0 = 0.50241928618815570551 / 2;
    private static final double COS1_1 = 0.52249861493968888062 / 2;
    private static final double COS1_2 = 0.56694403481635770368 / 2;
    private static final double COS1_3 = 0.64682178335999012954 / 2;
    private static final double COS1_4 = 0.78815462345125022473 / 2;
    private static final double COS1_5 = 1.06067768599034747134 / 4;
    private static final double COS1_6 = 1.72244709823833392782 / 4;
    private static final double COS1_7 = 5.10114861868916385802 / 16;
    private static final double COS2_0 = 0.50979557910415916894 / 2;
    private static final double COS2_1 = 0.60134488693504528054 / 2;
    private static final double COS2_2 = 0.89997622313641570463 / 2;
    private static final double COS2_3 = 2.56291544774150617881 / 8;
    private static final double COS3_0 = 0.54119610014619698439 / 2;
    private static final double COS3_1 = 1.30656296487637652785 / 4;
    private static final double COS4_0 = Math.sqrt(0.5) / 2;

    /** 标准 MPEG 合成窗口表 ff_mpa_enwindow[257]（ISO 11172-3，FFmpeg 定义） */
    private static final int[] ENWINDOW = {
            0,    -1,    -1,    -1,    -1,    -1,    -1,    -2,
            -2,    -2,    -2,    -3,    -3,    -4,    -4,    -5,
            -5,    -6,    -7,    -7,    -8,    -9,   -10,   -11,
            -13,   -14,   -16,   -17,   -19,   -21,   -24,   -26,
            -29,   -31,   -35,   -38,   -41,   -45,   -49,   -53,
            -58,   -63,   -68,   -73,   -79,   -85,   -91,   -97,
            -104,  -111,  -117,  -125,  -132,  -139,  -147,  -154,
            -161,  -169,  -176,  -183,  -190,  -196,  -202,  -208,
            213,   218,   222,   225,   227,   228,   228,   227,
            224,   221,   215,   208,   200,   189,   177,   163,
            146,   127,   106,    83,    57,    29,    -2,   -36,
            -72,  -111,  -153,  -197,  -244,  -294,  -347,  -401,
            -459,  -519,  -581,  -645,  -711,  -779,  -848,  -919,
            -991, -1064, -1137, -1210, -1283, -1356, -1428, -1498,
            -1567, -1634, -1698, -1759, -1817, -1870, -1919, -1962,
            -2001, -2032, -2057, -2075, -2085, -2087, -2080, -2063,
            2037,  2000,  1952,  1893,  1822,  1739,  1644,  1535,
            1414,  1280,  1131,   970,   794,   605,   402,   185,
            -45,  -288,  -545,  -814, -1095, -1388, -1692, -2006,
            -2330, -2663, -3004, -3351, -3705, -4063, -4425, -4788,
            -5153, -5517, -5879, -6237, -6589, -6935, -7271, -7597,
            -7910, -8209, -8491, -8755, -8998, -9219, -9416, -9585,
            -9727, -9838, -9916, -9959, -9966, -9935, -9863, -9750,
            -9592, -9389, -9139, -8840, -8492, -8092, -7640, -7134,
            6574,  5959,  5288,  4561,  3776,  2935,  2037,  1082,
            70,  -998, -2122, -3300, -4533, -5818, -7154, -8540,
            -9975,-11455,-12980,-14548,-16155,-17799,-19478,-21189,
            -22929,-24694,-26482,-28289,-30112,-31947,-33791,-35640,
            -37489,-39336,-41176,-43006,-44821,-46617,-48390,-50137,
            -51853,-53534,-55178,-56778,-58333,-59838,-61289,-62684,
            -64019,-65290,-66494,-67629,-68692,-69679,-70590,-71420,
            -72169,-72835,-73415,-73908,-74313,-74630,-74856,-74992,
            75038,
    };

    /** Layer II 位分配表 alloc_table_1（FFmpeg，192kbps/48kHz 立体声 → table=0） */
    private static final int[] ALLOC_TABLE_1 = new int[283];
    /** ff_mpa_quant_bits[17]：负数表示分组量化（3 样本一组） */
    private static final int[] QUANT_BITS = {-5, -7, 3, -10, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    /** 分组量化步数（qindex=0→3, 1→5, 3→9） */
    static {
        // 生成 alloc_table_1：sb0-2(4位×16), sb3-10(4位×16), sb11-20(3位×8), sb21-26(2位×4)
        int idx = 0;
        int[] seqA = {4, 0, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        int[] seqB = {4, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16};
        int[] seqC = {3, 0, 1, 2, 3, 4, 5, 16};
        int[] seqD = {2, 0, 1, 16};
        for (int i = 0; i < 3; i++) for (int v : seqA) ALLOC_TABLE_1[idx++] = v;
        for (int i = 0; i < 8; i++) for (int v : seqB) ALLOC_TABLE_1[idx++] = v;
        for (int i = 0; i < 10; i++) for (int v : seqC) ALLOC_TABLE_1[idx++] = v;
        for (int i = 0; i < 6; i++) for (int v : seqD) ALLOC_TABLE_1[idx++] = v;
    }

    static {
        // 512 点合成窗口表（FFmpeg mpa_synth_init 逻辑，除以 2^16 归一化）
        for (int i = 0; i < 257; i++) {
            double v = ENWINDOW[i];
            W[i] = v;
            if ((i & 63) != 0) v = -v;
            if (i != 0) W[512 - i] = v;
        }
        // 归一化：window_coeff = enwindow / 2^16（WFRAC_BITS=16）
        for (int n = 0; n < 512; n++) W[n] /= 65536.0;
    }

    /** 解码一帧 MP2 → PCM（16bit 交错）。每帧 1152 样本/通道。 */
    private short[] decodeFrame(byte[] data, int pos, FrameInfo fi) {
        int nCh = fi.channels;
        boolean mono = fi.channelMode == 3;
        BitReader br = new BitReader(data, pos + 4, pos + fi.frameLength);

        // MPEG-1 Layer II 位分配表：table=0（192kbps/48kHz 立体声），sblimit=27, bound=27
        int sblimit = 27;
        int bound = 27; // 立体声非联合（mode=0）
        int[][] bitAlloc = new int[2][32];
        int[][] scaleCode = new int[2][32];
        int[][][] scaleFactors = new int[2][32][3];

        // 1. 位分配（FFmpeg decode_layer2：j += 1 << bit_alloc_bits）
        int j = 0;
        for (int i = 0; i < bound; i++) {
            int bab = ALLOC_TABLE_1[j];
            for (int ch = 0; ch < nCh; ch++) bitAlloc[ch][i] = br.readBits(bab);
            j += 1 << bab;
        }

        // 2. scale_code（每子带 2 位，决定比例因子个数）
        for (int i = 0; i < sblimit; i++) {
            for (int ch = 0; ch < nCh; ch++) {
                if (bitAlloc[ch][i] != 0) scaleCode[ch][i] = br.readBits(2);
            }
        }

        // 3. 比例因子（按 scale_code 读 1/2/3 个 6 位）
        for (int i = 0; i < sblimit; i++) {
            for (int ch = 0; ch < nCh; ch++) {
                if (bitAlloc[ch][i] != 0) {
                    int sc = scaleCode[ch][i];
                    switch (sc) {
                        case 0:
                            scaleFactors[ch][i][0] = br.readBits(6);
                            scaleFactors[ch][i][1] = br.readBits(6);
                            scaleFactors[ch][i][2] = br.readBits(6);
                            break;
                        case 2:
                            scaleFactors[ch][i][0] = br.readBits(6);
                            scaleFactors[ch][i][1] = scaleFactors[ch][i][0];
                            scaleFactors[ch][i][2] = scaleFactors[ch][i][0];
                            break;
                        case 1:
                            scaleFactors[ch][i][0] = br.readBits(6);
                            scaleFactors[ch][i][2] = br.readBits(6);
                            scaleFactors[ch][i][1] = scaleFactors[ch][i][0];
                            break;
                        case 3:
                            scaleFactors[ch][i][0] = br.readBits(6);
                            scaleFactors[ch][i][2] = br.readBits(6);
                            scaleFactors[ch][i][1] = scaleFactors[ch][i][2];
                            break;
                    }
                }
            }
        }

        // 4. 样本：3 组 × 12 样本，分组量化（bits<0 时 3 样本一组）
        double[][][][] samples = new double[2][3][12][32];
        for (int k = 0; k < 3; k++) {
            for (int l = 0; l < 12; l += 3) {
                j = 0;
                for (int i = 0; i < bound; i++) {
                    int bab = ALLOC_TABLE_1[j];
                    for (int ch = 0; ch < nCh; ch++) {
                        int b = bitAlloc[ch][i];
                        if (b != 0) {
                            double scale = scaleFactors[ch][i][k];
                            int qindex = ALLOC_TABLE_1[j + b];
                            int bits = QUANT_BITS[qindex];
                            if (bits < 0) {
                                // 分组量化：3 个样本同时读 -bits 位
                                int v = br.readBits(-bits);
                                int steps = qindex == 0 ? 3 : (qindex == 1 ? 5 : 9);
                                int v0 = v % steps; v /= steps;
                                int v1 = v % steps; v /= steps;
                                int v2 = v;
                                samples[ch][k][l][i] = l2UnscaleGroup(steps, v0, scale);
                                samples[ch][k][l + 1][i] = l2UnscaleGroup(steps, v1, scale);
                                samples[ch][k][l + 2][i] = l2UnscaleGroup(steps, v2, scale);
                            } else {
                                // 线性量化：每样本 bits 位
                                for (int m = 0; m < 3; m++) {
                                    int mant = br.readBits(bits);
                                    samples[ch][k][l + m][i] = l1Unscale(bits - 1, mant, scale);
                                }
                            }
                        } else {
                            samples[ch][k][l][i] = 0;
                            samples[ch][k][l + 1][i] = 0;
                            samples[ch][k][l + 2][i] = 0;
                        }
                    }
                    j += 1 << bab;
                }
            }
        }

        // 5. 合成：FFmpeg dct32 + apply_window（32 子带 → 32 PCM，每块 32 样本）
        short[] out = new short[3 * 12 * 32 * nCh]; // 1152 * nCh
        int outIdx = 0;
        for (int grp = 0; grp < 3; grp++) {
            for (int samp = 0; samp < 12; samp++) {
                double[] inL = samples[0][grp][samp];
                if (mono) {
                    synthFilter(inL, 0, out, outIdx, 1);
                    outIdx += 32;
                } else {
                    synthFilter(inL, 0, out, outIdx, 2);
                    synthFilter(samples[1][grp][samp], 1, out, outIdx + 1, 2);
                    outIdx += 64;
                }
            }
        }
        logPcmStats(out);
        return out;
    }

    /** 每 N 帧输出一次 PCM 统计日志（确认解码器输出正常） */
    private void logPcmStats(short[] pcm) {
        if ((logFrameCount++ % 50) != 0) return;
        int peak = 0;
        int zcr = 0;
        for (int i = 0; i < pcm.length; i++) {
            int v = pcm[i];
            if (Math.abs(v) > peak) peak = Math.abs(v);
            if (i > 0 && (v > 0) != (pcm[i - 1] > 0)) zcr++;
        }
        double rms = 0;
        for (short s : pcm) rms += (double) s * s;
        rms = Math.sqrt(rms / pcm.length);
        // 分声道统计（判断莎莎声是真实高频还是交错伪影）
        int nCh = (pcm.length > 2048) ? 2 : 1; // 1152*2=2304 立体声，1152 单声道
        int lzcr = 0, rzcr = 0, lpeak = 0, rpeak = 0;
        if (nCh == 2) {
            for (int i = 0; i < pcm.length; i += 2) {
                int l = pcm[i], r = pcm[i + 1];
                if (Math.abs(l) > lpeak) lpeak = Math.abs(l);
                if (Math.abs(r) > rpeak) rpeak = Math.abs(r);
                if (i >= 2) {
                    if ((l > 0) != (pcm[i - 2] > 0)) lzcr++;
                    if ((r > 0) != (pcm[i - 1] > 0)) rzcr++;
                }
            }
            android.util.Log.i("MpegAudioDecoder", "帧#" + logFrameCount
                    + " peak=" + peak + " rms=" + (int) rms
                    + " zcr=" + (double) zcr / pcm.length
                    + " Lzcr=" + (double) lzcr / (pcm.length / 2)
                    + " Rzcr=" + (double) rzcr / (pcm.length / 2)
                    + " Lpeak=" + lpeak + " Rpeak=" + rpeak
                    + " len=" + pcm.length);
        } else {
            android.util.Log.i("MpegAudioDecoder", "帧#" + logFrameCount
                    + " peak=" + peak + " rms=" + (int) rms
                    + " zcr=" + (double) zcr / pcm.length
                    + " len=" + pcm.length);
        }
    }

    /** 线性逆量化（FFmpeg l1_unscale，浮点归一化约 ±1） */
    private double l1Unscale(int n, int mant, double scale) {
        // n = bits - 1，mant 是 bits 位无符号值
        // val = (mant - 2^n + 1) * 4/(2^(n+1)-1) * 2^(-sf/3)
        return (mant - (1 << n) + 1) * (4.0 / ((1 << (n + 1)) - 1)) * Math.pow(2.0, -scale / 3.0);
    }

    /** 分组量化逆量化（FFmpeg l2_unscale_group，浮点归一化约 ±1） */
    private double l2UnscaleGroup(int steps, int mant, double scale) {
        // val = (mant - steps/2) * 4/steps * 2^(-sf/3)
        return (mant - (steps >> 1)) * (4.0 / steps) * Math.pow(2.0, -scale / 3.0);
    }

    /** FFmpeg 32 子带合成滤波器组：dct32 + apply_window，写入 out[outIdx + stride*j] */
    private void synthFilter(double[] s, int ch, short[] out, int outIdx, int stride) {
        double[] synthBuf = SYNTH_BUF[ch];
        int offset = SYNTH_OFFSET[ch];
        // dct32：32 子带 → 32 值，写入 synth_buf[offset..offset+31]
        dct32(s, DCT_TMP, DCT_OUT);
        System.arraycopy(DCT_OUT, 0, synthBuf, offset, 32);
        // apply_window：用 synth_buf[offset..offset+543]（含历史）
        applyWindow(synthBuf, offset, PCM_TMP);
        for (int j = 0; j < 32; j++) {
            out[outIdx + stride * j] = (short) clamp((int) Math.round(PCM_TMP[j] * SCALE));
        }
        SYNTH_OFFSET[ch] = (offset - 32) & 511;
    }

    /** FFmpeg dct32（32 点 DCT，浮点，tab 输入 → out 输出） */
    private static void dct32(double[] tab, double[] val, double[] out) {
        double tmp0, tmp1;
        // BF0(0,31,COS0_0,1)
        tmp0 = tab[0] + tab[31]; tmp1 = tab[0] - tab[31];
        val[0] = tmp0; val[31] = tmp1 * COS0_0 * 2;
        // BF0(15,16,COS0_15,5)
        tmp0 = tab[15] + tab[16]; tmp1 = tab[15] - tab[16];
        val[15] = tmp0; val[16] = tmp1 * COS0_15 * 32;
        // BF(0,15,COS1_0,1)
        tmp0 = val[0] + val[15]; tmp1 = val[0] - val[15];
        val[0] = tmp0; val[15] = tmp1 * COS1_0 * 2;
        // BF(16,31,-COS1_0,1)
        tmp0 = val[16] + val[31]; tmp1 = val[16] - val[31];
        val[16] = tmp0; val[31] = tmp1 * (-COS1_0) * 2;
        // BF0(7,24,COS0_7,1)
        tmp0 = tab[7] + tab[24]; tmp1 = tab[7] - tab[24];
        val[7] = tmp0; val[24] = tmp1 * COS0_7 * 2;
        // BF0(8,23,COS0_8,1)
        tmp0 = tab[8] + tab[23]; tmp1 = tab[8] - tab[23];
        val[8] = tmp0; val[23] = tmp1 * COS0_8 * 2;
        // BF(7,8,COS1_7,4)
        tmp0 = val[7] + val[8]; tmp1 = val[7] - val[8];
        val[7] = tmp0; val[8] = tmp1 * COS1_7 * 16;
        // BF(23,24,-COS1_7,4)
        tmp0 = val[23] + val[24]; tmp1 = val[23] - val[24];
        val[23] = tmp0; val[24] = tmp1 * (-COS1_7) * 16;
        // BF(0,7,COS2_0,1)
        tmp0 = val[0] + val[7]; tmp1 = val[0] - val[7];
        val[0] = tmp0; val[7] = tmp1 * COS2_0 * 2;
        // BF(8,15,-COS2_0,1)
        tmp0 = val[8] + val[15]; tmp1 = val[8] - val[15];
        val[8] = tmp0; val[15] = tmp1 * (-COS2_0) * 2;
        // BF(16,23,COS2_0,1)
        tmp0 = val[16] + val[23]; tmp1 = val[16] - val[23];
        val[16] = tmp0; val[23] = tmp1 * COS2_0 * 2;
        // BF(24,31,-COS2_0,1)
        tmp0 = val[24] + val[31]; tmp1 = val[24] - val[31];
        val[24] = tmp0; val[31] = tmp1 * (-COS2_0) * 2;
        // BF0(3,28,COS0_3,1)
        tmp0 = tab[3] + tab[28]; tmp1 = tab[3] - tab[28];
        val[3] = tmp0; val[28] = tmp1 * COS0_3 * 2;
        // BF0(12,19,COS0_12,2)
        tmp0 = tab[12] + tab[19]; tmp1 = tab[12] - tab[19];
        val[12] = tmp0; val[19] = tmp1 * COS0_12 * 4;
        // BF(3,12,COS1_3,1)
        tmp0 = val[3] + val[12]; tmp1 = val[3] - val[12];
        val[3] = tmp0; val[12] = tmp1 * COS1_3 * 2;
        // BF(19,28,-COS1_3,1)
        tmp0 = val[19] + val[28]; tmp1 = val[19] - val[28];
        val[19] = tmp0; val[28] = tmp1 * (-COS1_3) * 2;
        // BF0(4,27,COS0_4,1)
        tmp0 = tab[4] + tab[27]; tmp1 = tab[4] - tab[27];
        val[4] = tmp0; val[27] = tmp1 * COS0_4 * 2;
        // BF0(11,20,COS0_11,2)
        tmp0 = tab[11] + tab[20]; tmp1 = tab[11] - tab[20];
        val[11] = tmp0; val[20] = tmp1 * COS0_11 * 4;
        // BF(4,11,COS1_4,1)
        tmp0 = val[4] + val[11]; tmp1 = val[4] - val[11];
        val[4] = tmp0; val[11] = tmp1 * COS1_4 * 2;
        // BF(20,27,-COS1_4,1)
        tmp0 = val[20] + val[27]; tmp1 = val[20] - val[27];
        val[20] = tmp0; val[27] = tmp1 * (-COS1_4) * 2;
        // BF(3,4,COS2_3,3)
        tmp0 = val[3] + val[4]; tmp1 = val[3] - val[4];
        val[3] = tmp0; val[4] = tmp1 * COS2_3 * 8;
        // BF(11,12,-COS2_3,3)
        tmp0 = val[11] + val[12]; tmp1 = val[11] - val[12];
        val[11] = tmp0; val[12] = tmp1 * (-COS2_3) * 8;
        // BF(19,20,COS2_3,3)
        tmp0 = val[19] + val[20]; tmp1 = val[19] - val[20];
        val[19] = tmp0; val[20] = tmp1 * COS2_3 * 8;
        // BF(27,28,-COS2_3,3)
        tmp0 = val[27] + val[28]; tmp1 = val[27] - val[28];
        val[27] = tmp0; val[28] = tmp1 * (-COS2_3) * 8;
        // BF(0,3,COS3_0,1)
        tmp0 = val[0] + val[3]; tmp1 = val[0] - val[3];
        val[0] = tmp0; val[3] = tmp1 * COS3_0 * 2;
        // BF(4,7,-COS3_0,1)
        tmp0 = val[4] + val[7]; tmp1 = val[4] - val[7];
        val[4] = tmp0; val[7] = tmp1 * (-COS3_0) * 2;
        // BF(8,11,COS3_0,1)
        tmp0 = val[8] + val[11]; tmp1 = val[8] - val[11];
        val[8] = tmp0; val[11] = tmp1 * COS3_0 * 2;
        // BF(12,15,-COS3_0,1)
        tmp0 = val[12] + val[15]; tmp1 = val[12] - val[15];
        val[12] = tmp0; val[15] = tmp1 * (-COS3_0) * 2;
        // BF(16,19,COS3_0,1)
        tmp0 = val[16] + val[19]; tmp1 = val[16] - val[19];
        val[16] = tmp0; val[19] = tmp1 * COS3_0 * 2;
        // BF(20,23,-COS3_0,1)
        tmp0 = val[20] + val[23]; tmp1 = val[20] - val[23];
        val[20] = tmp0; val[23] = tmp1 * (-COS3_0) * 2;
        // BF(24,27,COS3_0,1)
        tmp0 = val[24] + val[27]; tmp1 = val[24] - val[27];
        val[24] = tmp0; val[27] = tmp1 * COS3_0 * 2;
        // BF(28,31,-COS3_0,1)
        tmp0 = val[28] + val[31]; tmp1 = val[28] - val[31];
        val[28] = tmp0; val[31] = tmp1 * (-COS3_0) * 2;
        // BF0(1,30,COS0_1,1)
        tmp0 = tab[1] + tab[30]; tmp1 = tab[1] - tab[30];
        val[1] = tmp0; val[30] = tmp1 * COS0_1 * 2;
        // BF0(14,17,COS0_14,3)
        tmp0 = tab[14] + tab[17]; tmp1 = tab[14] - tab[17];
        val[14] = tmp0; val[17] = tmp1 * COS0_14 * 8;
        // BF(1,14,COS1_1,1)
        tmp0 = val[1] + val[14]; tmp1 = val[1] - val[14];
        val[1] = tmp0; val[14] = tmp1 * COS1_1 * 2;
        // BF(17,30,-COS1_1,1)
        tmp0 = val[17] + val[30]; tmp1 = val[17] - val[30];
        val[17] = tmp0; val[30] = tmp1 * (-COS1_1) * 2;
        // BF0(6,25,COS0_6,1)
        tmp0 = tab[6] + tab[25]; tmp1 = tab[6] - tab[25];
        val[6] = tmp0; val[25] = tmp1 * COS0_6 * 2;
        // BF0(9,22,COS0_9,1)
        tmp0 = tab[9] + tab[22]; tmp1 = tab[9] - tab[22];
        val[9] = tmp0; val[22] = tmp1 * COS0_9 * 2;
        // BF(6,9,COS1_6,2)
        tmp0 = val[6] + val[9]; tmp1 = val[6] - val[9];
        val[6] = tmp0; val[9] = tmp1 * COS1_6 * 4;
        // BF(22,25,-COS1_6,2)
        tmp0 = val[22] + val[25]; tmp1 = val[22] - val[25];
        val[22] = tmp0; val[25] = tmp1 * (-COS1_6) * 4;
        // BF(1,6,COS2_1,1)
        tmp0 = val[1] + val[6]; tmp1 = val[1] - val[6];
        val[1] = tmp0; val[6] = tmp1 * COS2_1 * 2;
        // BF(9,14,-COS2_1,1)
        tmp0 = val[9] + val[14]; tmp1 = val[9] - val[14];
        val[9] = tmp0; val[14] = tmp1 * (-COS2_1) * 2;
        // BF(17,22,COS2_1,1)
        tmp0 = val[17] + val[22]; tmp1 = val[17] - val[22];
        val[17] = tmp0; val[22] = tmp1 * COS2_1 * 2;
        // BF(25,30,-COS2_1,1)
        tmp0 = val[25] + val[30]; tmp1 = val[25] - val[30];
        val[25] = tmp0; val[30] = tmp1 * (-COS2_1) * 2;
        // BF0(2,29,COS0_2,1)
        tmp0 = tab[2] + tab[29]; tmp1 = tab[2] - tab[29];
        val[2] = tmp0; val[29] = tmp1 * COS0_2 * 2;
        // BF0(13,18,COS0_13,3)
        tmp0 = tab[13] + tab[18]; tmp1 = tab[13] - tab[18];
        val[13] = tmp0; val[18] = tmp1 * COS0_13 * 8;
        // BF(2,13,COS1_2,1)
        tmp0 = val[2] + val[13]; tmp1 = val[2] - val[13];
        val[2] = tmp0; val[13] = tmp1 * COS1_2 * 2;
        // BF(18,29,-COS1_2,1)
        tmp0 = val[18] + val[29]; tmp1 = val[18] - val[29];
        val[18] = tmp0; val[29] = tmp1 * (-COS1_2) * 2;
        // BF0(5,26,COS0_5,1)
        tmp0 = tab[5] + tab[26]; tmp1 = tab[5] - tab[26];
        val[5] = tmp0; val[26] = tmp1 * COS0_5 * 2;
        // BF0(10,21,COS0_10,1)
        tmp0 = tab[10] + tab[21]; tmp1 = tab[10] - tab[21];
        val[10] = tmp0; val[21] = tmp1 * COS0_10 * 2;
        // BF(5,10,COS1_5,2)
        tmp0 = val[5] + val[10]; tmp1 = val[5] - val[10];
        val[5] = tmp0; val[10] = tmp1 * COS1_5 * 4;
        // BF(21,26,-COS1_5,2)
        tmp0 = val[21] + val[26]; tmp1 = val[21] - val[26];
        val[21] = tmp0; val[26] = tmp1 * (-COS1_5) * 4;
        // BF(2,5,COS2_2,1)
        tmp0 = val[2] + val[5]; tmp1 = val[2] - val[5];
        val[2] = tmp0; val[5] = tmp1 * COS2_2 * 2;
        // BF(10,13,-COS2_2,1)
        tmp0 = val[10] + val[13]; tmp1 = val[10] - val[13];
        val[10] = tmp0; val[13] = tmp1 * (-COS2_2) * 2;
        // BF(18,21,COS2_2,1)
        tmp0 = val[18] + val[21]; tmp1 = val[18] - val[21];
        val[18] = tmp0; val[21] = tmp1 * COS2_2 * 2;
        // BF(26,29,-COS2_2,1)
        tmp0 = val[26] + val[29]; tmp1 = val[26] - val[29];
        val[26] = tmp0; val[29] = tmp1 * (-COS2_2) * 2;
        // BF(1,2,COS3_1,2)
        tmp0 = val[1] + val[2]; tmp1 = val[1] - val[2];
        val[1] = tmp0; val[2] = tmp1 * COS3_1 * 4;
        // BF(5,6,-COS3_1,2)
        tmp0 = val[5] + val[6]; tmp1 = val[5] - val[6];
        val[5] = tmp0; val[6] = tmp1 * (-COS3_1) * 4;
        // BF(9,10,COS3_1,2)
        tmp0 = val[9] + val[10]; tmp1 = val[9] - val[10];
        val[9] = tmp0; val[10] = tmp1 * COS3_1 * 4;
        // BF(13,14,-COS3_1,2)
        tmp0 = val[13] + val[14]; tmp1 = val[13] - val[14];
        val[13] = tmp0; val[14] = tmp1 * (-COS3_1) * 4;
        // BF(17,18,COS3_1,2)
        tmp0 = val[17] + val[18]; tmp1 = val[17] - val[18];
        val[17] = tmp0; val[18] = tmp1 * COS3_1 * 4;
        // BF(21,22,-COS3_1,2)
        tmp0 = val[21] + val[22]; tmp1 = val[21] - val[22];
        val[21] = tmp0; val[22] = tmp1 * (-COS3_1) * 4;
        // BF(25,26,COS3_1,2)
        tmp0 = val[25] + val[26]; tmp1 = val[25] - val[26];
        val[25] = tmp0; val[26] = tmp1 * COS3_1 * 4;
        // BF(29,30,-COS3_1,2)
        tmp0 = val[29] + val[30]; tmp1 = val[29] - val[30];
        val[29] = tmp0; val[30] = tmp1 * (-COS3_1) * 4;
        // BF1(0,1,2,3): BF(0,1,COS4_0,1); BF(2,3,-COS4_0,1); val[2]+=val[3]
        tmp0 = val[0] + val[1]; tmp1 = val[0] - val[1];
        val[0] = tmp0; val[1] = tmp1 * COS4_0 * 2;
        tmp0 = val[2] + val[3]; tmp1 = val[2] - val[3];
        val[2] = tmp0; val[3] = tmp1 * (-COS4_0) * 2;
        val[2] += val[3];
        // BF2(4,5,6,7): BF(4,5,COS4_0,1); BF(6,7,-COS4_0,1); val[6]+=val[7]; val[4]+=val[6]; val[6]+=val[5]; val[5]+=val[7]
        tmp0 = val[4] + val[5]; tmp1 = val[4] - val[5];
        val[4] = tmp0; val[5] = tmp1 * COS4_0 * 2;
        tmp0 = val[6] + val[7]; tmp1 = val[6] - val[7];
        val[6] = tmp0; val[7] = tmp1 * (-COS4_0) * 2;
        val[6] += val[7];
        val[4] += val[6];
        val[6] += val[5];
        val[5] += val[7];
        // BF1(8,9,10,11)
        tmp0 = val[8] + val[9]; tmp1 = val[8] - val[9];
        val[8] = tmp0; val[9] = tmp1 * COS4_0 * 2;
        tmp0 = val[10] + val[11]; tmp1 = val[10] - val[11];
        val[10] = tmp0; val[11] = tmp1 * (-COS4_0) * 2;
        val[10] += val[11];
        // BF2(12,13,14,15)
        tmp0 = val[12] + val[13]; tmp1 = val[12] - val[13];
        val[12] = tmp0; val[13] = tmp1 * COS4_0 * 2;
        tmp0 = val[14] + val[15]; tmp1 = val[14] - val[15];
        val[14] = tmp0; val[15] = tmp1 * (-COS4_0) * 2;
        val[14] += val[15];
        val[12] += val[14];
        val[14] += val[13];
        val[13] += val[15];
        // BF1(16,17,18,19)
        tmp0 = val[16] + val[17]; tmp1 = val[16] - val[17];
        val[16] = tmp0; val[17] = tmp1 * COS4_0 * 2;
        tmp0 = val[18] + val[19]; tmp1 = val[18] - val[19];
        val[18] = tmp0; val[19] = tmp1 * (-COS4_0) * 2;
        val[18] += val[19];
        // BF2(20,21,22,23)
        tmp0 = val[20] + val[21]; tmp1 = val[20] - val[21];
        val[20] = tmp0; val[21] = tmp1 * COS4_0 * 2;
        tmp0 = val[22] + val[23]; tmp1 = val[22] - val[23];
        val[22] = tmp0; val[23] = tmp1 * (-COS4_0) * 2;
        val[22] += val[23];
        val[20] += val[22];
        val[22] += val[21];
        val[21] += val[23];
        // BF1(24,25,26,27)
        tmp0 = val[24] + val[25]; tmp1 = val[24] - val[25];
        val[24] = tmp0; val[25] = tmp1 * COS4_0 * 2;
        tmp0 = val[26] + val[27]; tmp1 = val[26] - val[27];
        val[26] = tmp0; val[27] = tmp1 * (-COS4_0) * 2;
        val[26] += val[27];
        // BF2(28,29,30,31)
        tmp0 = val[28] + val[29]; tmp1 = val[28] - val[29];
        val[28] = tmp0; val[29] = tmp1 * COS4_0 * 2;
        tmp0 = val[30] + val[31]; tmp1 = val[30] - val[31];
        val[30] = tmp0; val[31] = tmp1 * (-COS4_0) * 2;
        val[30] += val[31];
        val[28] += val[30];
        val[30] += val[29];
        val[29] += val[31];
        // ADD(8,12); ADD(12,10); ADD(10,14); ADD(14,9); ADD(9,13); ADD(13,11); ADD(11,15)
        val[8] += val[12]; val[12] += val[10]; val[10] += val[14]; val[14] += val[9];
        val[9] += val[13]; val[13] += val[11]; val[11] += val[15];
        // 输出（重排）
        out[0] = val[0]; out[16] = val[1]; out[8] = val[2]; out[24] = val[3];
        out[4] = val[4]; out[20] = val[5]; out[12] = val[6]; out[28] = val[7];
        out[2] = val[8]; out[18] = val[9]; out[10] = val[10]; out[26] = val[11];
        out[6] = val[12]; out[22] = val[13]; out[14] = val[14]; out[30] = val[15];
        // ADD(24,28); ADD(28,26); ADD(26,30); ADD(30,25); ADD(25,29); ADD(29,27); ADD(27,31)
        val[24] += val[28]; val[28] += val[26]; val[26] += val[30]; val[30] += val[25];
        val[25] += val[29]; val[29] += val[27]; val[27] += val[31];
        // 第二半输出
        out[1] = val[16] + val[24]; out[17] = val[17] + val[25];
        out[9] = val[18] + val[26]; out[25] = val[19] + val[27];
        out[5] = val[20] + val[28]; out[21] = val[21] + val[29];
        out[13] = val[22] + val[30]; out[29] = val[23] + val[31];
        out[3] = val[24] + val[20]; out[19] = val[25] + val[21];
        out[11] = val[26] + val[22]; out[27] = val[27] + val[23];
        out[7] = val[28] + val[18]; out[23] = val[29] + val[19];
        out[15] = val[30] + val[17]; out[31] = val[31];
    }

    /** FFmpeg apply_window：synth_buf[offset..offset+543] → 32 PCM（W 归一化后） */
    private static void applyWindow(double[] sb, int offset, double[] out) {
        // memcpy(sb+512, sb, 32)：sb[512..543] = sb[0..31]（相对 offset）
        for (int i = 0; i < 32; i++) {
            sb[offset + 512 + i] = sb[offset + i];
        }
        int w = 0;
        double sum = 0;
        // 样本 0
        for (int i = 0; i < 8; i++) sum += W[w + i * 64] * sb[offset + 16 + i * 64];
        for (int i = 0; i < 8; i++) sum -= W[w + 32 + i * 64] * sb[offset + 48 + i * 64];
        out[0] = sum;
        w = 1;
        for (int j = 1; j < 16; j++) {
            double sum2 = 0;
            // p = 16+j
            for (int i = 0; i < 8; i++) {
                double p = sb[offset + 16 + j + i * 64];
                sum += W[w + i * 64] * p;
                sum2 -= W[32 - j + i * 64] * p;
            }
            // p = 48-j
            for (int i = 0; i < 8; i++) {
                double p = sb[offset + 48 - j + i * 64];
                sum -= W[w + 32 + i * 64] * p;
                sum2 -= W[32 - j + 32 + i * 64] * p;
            }
            out[j] = sum;
            sum += sum2;
            out[31 - j] = sum;
            w++;
        }
        // 最后样本
        for (int i = 0; i < 8; i++) sum -= W[w + 32 + i * 64] * sb[offset + 32 + i * 64];
        out[31] = sum;
    }

    /** 输出增益：FFmpeg 合成输出（窗口归一化后）峰值约 21，乘 1024 得约 22000（int16 合理） */
    private static final double SCALE = 512.0;

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

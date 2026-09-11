#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include <jni.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/display.h>
#include <libavutil/error.h>
#include <libavutil/rational.h>
#include <libswscale/swscale.h>
}

#include "jni_utils.h"
#include "log.h"

extern "C" {
    jni_func(jobject, extractThumbnail, jstring source, jlong timestamp_ms,
        jint max_width, jint max_height, jlong timeout_ms);
}

namespace {

class UtfChars {
public:
    UtfChars(JNIEnv *env, jstring value)
        : env_(env), value_(value), chars_(env->GetStringUTFChars(value, nullptr)) {}

    ~UtfChars() {
        if (chars_)
            env_->ReleaseStringUTFChars(value_, chars_);
    }

    const char *get() const { return chars_; }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

struct InterruptContext {
    JNIEnv *env;
    jobject thread;
    jmethodID is_interrupted;
    std::chrono::steady_clock::time_point deadline;
    bool timed_out;
    bool interrupted;
};

int interrupt_callback(void *opaque) {
    auto *context = static_cast<InterruptContext *>(opaque);
    if (std::chrono::steady_clock::now() >= context->deadline) {
        context->timed_out = true;
        return 1;
    }

    const bool interrupted = context->thread && context->is_interrupted &&
        context->env->CallBooleanMethod(context->thread, context->is_interrupted);
    if (interrupted) {
        context->interrupted = true;
        return 1;
    }
    return 0;
}

void log_ffmpeg_error(const char *operation, int error) {
    char message[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(error, message, sizeof(message));
    ALOGW("Thumbnail %s failed: %s", operation, message);
}

int64_t choose_timestamp_ms(const AVFormatContext *format, int64_t requested_timestamp_ms) {
    if (requested_timestamp_ms >= 0)
        return requested_timestamp_ms;
    if (format->duration <= 0)
        return 30000;

    const int64_t duration_ms = format->duration / (AV_TIME_BASE / 1000);
    if (duration_ms <= 0)
        return 0;
    return std::min(duration_ms / 10, std::max<int64_t>(0, duration_ms - 100));
}

void calculate_output_size(
    AVFormatContext *format,
    AVStream *stream,
    AVFrame *frame,
    int quarter_turns,
    int max_width,
    int max_height,
    int *output_width,
    int *output_height
) {
    AVRational aspect = av_guess_sample_aspect_ratio(format, stream, frame);
    double display_width = frame->width;
    if (aspect.num > 0 && aspect.den > 0)
        display_width *= av_q2d(aspect);
    const bool swap_dimensions = quarter_turns % 2 != 0;
    const double final_width = swap_dimensions ? frame->height : display_width;
    const double final_height = swap_dimensions ? display_width : frame->height;

    const double scale = std::min(
        static_cast<double>(max_width) / final_width,
        static_cast<double>(max_height) / final_height
    );
    *output_width = std::max(1, static_cast<int>(std::lround(display_width * scale)));
    *output_height = std::max(1, static_cast<int>(std::lround(frame->height * scale)));
}

int get_quarter_turns(const AVCodecContext *decoder, const AVFrame *frame) {
    const AVFrameSideData *display_matrix =
        av_frame_get_side_data(frame, AV_FRAME_DATA_DISPLAYMATRIX);
    if (!display_matrix) {
        for (int index = 0; index < decoder->nb_decoded_side_data; ++index) {
            if (decoder->decoded_side_data[index]->type == AV_FRAME_DATA_DISPLAYMATRIX) {
                display_matrix = decoder->decoded_side_data[index];
                break;
            }
        }
    }
    if (!display_matrix || display_matrix->size < 9 * sizeof(int32_t))
        return 0;

    const double angle = av_display_rotation_get(
        reinterpret_cast<const int32_t *>(display_matrix->data));
    if (std::isnan(angle))
        return 0;
    const int turns = static_cast<int>(std::lround(angle / 90.0));
    return (turns % 4 + 4) % 4;
}

jobject create_bitmap(
    JNIEnv *env,
    AVFormatContext *format,
    AVStream *stream,
    AVCodecContext *decoder,
    AVFrame *frame,
    int max_width,
    int max_height
) {
    const int quarter_turns = get_quarter_turns(decoder, frame);
    int output_width = 0;
    int output_height = 0;
    calculate_output_size(
        format,
        stream,
        frame,
        quarter_turns,
        max_width,
        max_height,
        &output_width,
        &output_height
    );

    SwsContext *scale_context = sws_getContext(
        frame->width,
        frame->height,
        static_cast<AVPixelFormat>(frame->format),
        output_width,
        output_height,
        AV_PIX_FMT_BGRA,
        SWS_BICUBIC,
        nullptr,
        nullptr,
        nullptr
    );
    if (!scale_context)
        return nullptr;

    std::vector<jint> pixels(static_cast<size_t>(output_width) * output_height);
    uint8_t *destination_data[4] = {
        reinterpret_cast<uint8_t *>(pixels.data()), nullptr, nullptr, nullptr
    };
    int destination_linesize[4] = {output_width * static_cast<int>(sizeof(jint)), 0, 0, 0};
    const int scaled_height = sws_scale(
        scale_context,
        frame->data,
        frame->linesize,
        0,
        frame->height,
        destination_data,
        destination_linesize
    );
    sws_freeContext(scale_context);
    if (scaled_height != output_height)
        return nullptr;

    if (quarter_turns != 0) {
        const int rotated_width = quarter_turns % 2 == 0 ? output_width : output_height;
        const int rotated_height = quarter_turns % 2 == 0 ? output_height : output_width;
        std::vector<jint> rotated(static_cast<size_t>(rotated_width) * rotated_height);
        for (int y = 0; y < output_height; ++y) {
            for (int x = 0; x < output_width; ++x) {
                int rotated_x = 0;
                int rotated_y = 0;
                if (quarter_turns == 1) {
                    rotated_x = y;
                    rotated_y = output_width - 1 - x;
                } else if (quarter_turns == 2) {
                    rotated_x = output_width - 1 - x;
                    rotated_y = output_height - 1 - y;
                } else {
                    rotated_x = output_height - 1 - y;
                    rotated_y = x;
                }
                rotated[rotated_y * rotated_width + rotated_x] =
                    pixels[y * output_width + x];
            }
        }
        pixels.swap(rotated);
        output_width = rotated_width;
        output_height = rotated_height;
    }

    jintArray array = env->NewIntArray(output_width * output_height);
    if (!array)
        return nullptr;
    env->SetIntArrayRegion(
        array,
        0,
        output_width * output_height,
        pixels.data()
    );

    jobject bitmap_config = env->GetStaticObjectField(
        android_graphics_Bitmap_Config,
        android_graphics_Bitmap_Config_ARGB_8888
    );
    jobject bitmap = env->CallStaticObjectMethod(
        android_graphics_Bitmap,
        android_graphics_Bitmap_createBitmap,
        array,
        output_width,
        output_height,
        bitmap_config
    );
    env->DeleteLocalRef(array);
    env->DeleteLocalRef(bitmap_config);
    return bitmap;
}

void throw_interrupted(JNIEnv *env) {
    jclass exception_class = env->FindClass("java/lang/InterruptedException");
    if (exception_class) {
        env->ThrowNew(exception_class, "Thumbnail extraction was cancelled");
        env->DeleteLocalRef(exception_class);
    }
}

} // namespace

jni_func(jobject, extractThumbnail, jstring source, jlong timestamp_ms,
    jint max_width, jint max_height, jlong timeout_ms) {
    if (!source || max_width <= 0 || max_height <= 0 || timeout_ms <= 0)
        return nullptr;

    init_methods_cache(env);

    UtfChars source_chars(env, source);
    if (!source_chars.get())
        return nullptr;

    jobject current_java_thread = nullptr;
    jmethodID is_interrupted = nullptr;
    jclass thread_class = env->FindClass("java/lang/Thread");
    if (thread_class) {
        jmethodID current_thread = env->GetStaticMethodID(
            thread_class, "currentThread", "()Ljava/lang/Thread;");
        is_interrupted = env->GetMethodID(thread_class, "isInterrupted", "()Z");
        if (current_thread)
            current_java_thread = env->CallStaticObjectMethod(thread_class, current_thread);
        env->DeleteLocalRef(thread_class);
    }

    InterruptContext interrupt_context{
        env,
        current_java_thread,
        is_interrupted,
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms),
        false,
        false,
    };
    AVFormatContext *format = avformat_alloc_context();
    AVCodecContext *decoder = nullptr;
    AVPacket *packet = nullptr;
    AVFrame *frame = nullptr;
    jobject bitmap = nullptr;
    const AVCodec *codec = nullptr;
    AVStream *stream = nullptr;
    int video_stream_index = -1;
    int result = 0;
    int64_t target_ms = 0;
    int64_t target_timestamp = 0;

    if (!format)
        return nullptr;
    format->interrupt_callback.callback = interrupt_callback;
    format->interrupt_callback.opaque = &interrupt_context;

    result = avformat_open_input(&format, source_chars.get(), nullptr, nullptr);
    if (result < 0) {
        log_ffmpeg_error("open", result);
        goto cleanup;
    }
    result = avformat_find_stream_info(format, nullptr);
    if (result < 0) {
        log_ffmpeg_error("stream discovery", result);
        goto cleanup;
    }

    video_stream_index = av_find_best_stream(
        format, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (video_stream_index < 0 || !codec) {
        log_ffmpeg_error("video stream discovery", video_stream_index);
        goto cleanup;
    }
    stream = format->streams[video_stream_index];

    decoder = avcodec_alloc_context3(codec);
    if (!decoder)
        goto cleanup;
    result = avcodec_parameters_to_context(decoder, stream->codecpar);
    if (result < 0) {
        log_ffmpeg_error("decoder parameters", result);
        goto cleanup;
    }
    decoder->thread_count = 0;
    result = avcodec_open2(decoder, codec, nullptr);
    if (result < 0) {
        log_ffmpeg_error("decoder open", result);
        goto cleanup;
    }

    target_ms = choose_timestamp_ms(format, timestamp_ms);
    target_timestamp = av_rescale_q(
        target_ms, AVRational{1, 1000}, stream->time_base);
    result = avformat_seek_file(
        format,
        video_stream_index,
        std::numeric_limits<int64_t>::min(),
        target_timestamp,
        std::numeric_limits<int64_t>::max(),
        AVSEEK_FLAG_BACKWARD
    );
    if (result < 0) {
        result = av_seek_frame(
            format, video_stream_index, target_timestamp, AVSEEK_FLAG_BACKWARD);
    }
    if (result < 0 && target_timestamp > 0) {
        target_timestamp = 0;
        result = av_seek_frame(format, video_stream_index, 0, AVSEEK_FLAG_BACKWARD);
    }
    if (result < 0) {
        log_ffmpeg_error("seek", result);
        goto cleanup;
    }
    avcodec_flush_buffers(decoder);

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!packet || !frame)
        goto cleanup;

    while ((result = av_read_frame(format, packet)) >= 0) {
        if (packet->stream_index != video_stream_index) {
            av_packet_unref(packet);
            continue;
        }
        result = avcodec_send_packet(decoder, packet);
        av_packet_unref(packet);
        if (result < 0 && result != AVERROR(EAGAIN)) {
            log_ffmpeg_error("packet decode", result);
            goto cleanup;
        }

        while ((result = avcodec_receive_frame(decoder, frame)) >= 0) {
            const int64_t frame_timestamp = frame->best_effort_timestamp;
            if (frame_timestamp == AV_NOPTS_VALUE || frame_timestamp >= target_timestamp) {
                bitmap = create_bitmap(
                    env, format, stream, decoder, frame, max_width, max_height);
                goto cleanup;
            }
            av_frame_unref(frame);
        }
        if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
            log_ffmpeg_error("frame decode", result);
            goto cleanup;
        }
    }

    if (result == AVERROR_EOF) {
        result = avcodec_send_packet(decoder, nullptr);
        if (result >= 0 || result == AVERROR_EOF) {
            while ((result = avcodec_receive_frame(decoder, frame)) >= 0) {
                const int64_t frame_timestamp = frame->best_effort_timestamp;
                if (frame_timestamp == AV_NOPTS_VALUE ||
                    frame_timestamp >= target_timestamp) {
                    bitmap = create_bitmap(
                        env, format, stream, decoder, frame, max_width, max_height);
                    goto cleanup;
                }
                av_frame_unref(frame);
            }
        }
    } else {
        log_ffmpeg_error("read", result);
    }

cleanup:
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&decoder);
    avformat_close_input(&format);
    if (current_java_thread)
        env->DeleteLocalRef(current_java_thread);
    if (interrupt_context.interrupted) {
        if (bitmap)
            env->DeleteLocalRef(bitmap);
        throw_interrupted(env);
        return nullptr;
    }
    if (interrupt_context.timed_out)
        ALOGW("Thumbnail extraction timed out");
    return bitmap;
}

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

constexpr int kMaxSequences = 16;
constexpr int kMaxAudioSeconds = 30;

void log_callback(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, "digup-llama", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, "digup-llama", text);
    }
}

void throw_java(JNIEnv * env, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

std::string utf16_to_utf8(const jchar * chars, jsize length) {
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

std::string java_string(JNIEnv * env, jstring string) {
    if (string == nullptr) return {};
    const jchar * chars = env->GetStringChars(string, nullptr);
    const jsize length = env->GetStringLength(string);
    std::string out = utf16_to_utf8(chars, length);
    env->ReleaseStringChars(string, chars);
    return out;
}

struct ImageSize {
    int width;
    int height;
};

bool target_size(int height, int width, int budget, ImageSize * out) {
    if (height <= 0 || width <= 0) return false;
    const int patch = 16, pooling = 3, side = pooling * patch;
    const double max_patches = static_cast<double>(budget * pooling * pooling);
    const double factor = std::sqrt((max_patches * patch * patch) / (static_cast<double>(height) * width));
    int target_h = static_cast<int>(std::floor(factor * height / side)) * side;
    int target_w = static_cast<int>(std::floor(factor * width / side)) * side;
    if (target_h == 0 && target_w == 0) return false;
    const int max_side = (budget / (pooling * pooling)) * side;
    if (target_h == 0) {
        target_h = side;
        target_w = std::min(static_cast<int>(static_cast<double>(width) / height * side), max_side);
    } else if (target_w == 0) {
        target_w = side;
        target_h = std::min(static_cast<int>(static_cast<double>(height) / width * side), max_side);
    }
    if (target_h <= 0 || target_w <= 0) return false;
    out->width = target_w;
    out->height = target_h;
    return true;
}

constexpr int kPrecision = 22;

double cubic_filter(double value) {
    const double x = std::fabs(value), a = -0.5;
    if (x < 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
    if (x < 2.0) return (((x - 5.0) * x + 8.0) * x - 4.0) * a;
    return 0.0;
}

struct Coefficients {
    std::vector<int> bounds;
    std::vector<int32_t> weights;
    int size = 0;
};

Coefficients coefficients(int in_size, int out_size) {
    Coefficients result;
    const double scale = static_cast<double>(in_size) / out_size;
    const double filter_scale = std::max(scale, 1.0);
    const double support = 2.0 * filter_scale;
    result.size = static_cast<int>(std::ceil(support)) * 2 + 1;
    result.bounds.assign(static_cast<size_t>(out_size) * 2, 0);
    result.weights.assign(static_cast<size_t>(out_size) * result.size, 0);
    std::vector<double> row(static_cast<size_t>(result.size), 0.0);
    for (int out = 0; out < out_size; ++out) {
        const double center = (static_cast<double>(out) + 0.5) * scale;
        const int first = std::max(static_cast<int>(center - support + 0.5), 0);
        const int count = std::min(static_cast<int>(center + support + 0.5), in_size) - first;
        double total = 0.0;
        for (int x = 0; x < result.size; ++x) {
            row[x] = x < count ? cubic_filter((static_cast<double>(x + first) - center + 0.5) / filter_scale) : 0.0;
            total += row[x];
        }
        for (int x = 0; x < result.size; ++x) {
            const double weight = (x < count && total != 0.0) ? row[x] / total : row[x];
            result.weights[static_cast<size_t>(out) * result.size + x] =
                static_cast<int32_t>(weight * static_cast<double>(1 << kPrecision) + (weight < 0 ? -0.5 : 0.5));
        }
        result.bounds[static_cast<size_t>(out) * 2] = first;
        result.bounds[static_cast<size_t>(out) * 2 + 1] = count;
    }
    return result;
}

inline uint8_t clip_channel(int32_t value) {
    const int32_t shifted = value >> kPrecision;
    return static_cast<uint8_t>(std::min<int32_t>(255, std::max<int32_t>(0, shifted)));
}

std::vector<uint8_t> resize_horizontal(const uint8_t * src, int in_width, int rows, int out_width) {
    const Coefficients c = coefficients(in_width, out_width);
    std::vector<uint8_t> out(static_cast<size_t>(out_width) * rows * 3, 0);
    for (int y = 0; y < rows; ++y) {
        for (int x = 0; x < out_width; ++x) {
            const uint8_t * p = src + (static_cast<size_t>(y) * in_width + c.bounds[x * 2]) * 3;
            int32_t r = 1 << (kPrecision - 1), g = r, b = r;
            const int count = c.bounds[x * 2 + 1];
            for (int k = 0; k < count; ++k) {
                const int32_t w = c.weights[static_cast<size_t>(x) * c.size + k];
                r += static_cast<int32_t>(p[0]) * w;
                g += static_cast<int32_t>(p[1]) * w;
                b += static_cast<int32_t>(p[2]) * w;
                p += 3;
            }
            const size_t o = (static_cast<size_t>(y) * out_width + x) * 3;
            out[o] = clip_channel(r);
            out[o + 1] = clip_channel(g);
            out[o + 2] = clip_channel(b);
        }
    }
    return out;
}

std::vector<uint8_t> resize_vertical(const uint8_t * src, int in_height, int row_width, int out_height) {
    const Coefficients c = coefficients(in_height, out_height);
    const size_t stride = static_cast<size_t>(row_width) * 3;
    std::vector<uint8_t> out(stride * out_height, 0);
    std::vector<int32_t> sums(stride, 0);
    for (int y = 0; y < out_height; ++y) {
        std::fill(sums.begin(), sums.end(), 1 << (kPrecision - 1));
        const int count = c.bounds[y * 2 + 1];
        for (int k = 0; k < count; ++k) {
            const uint8_t * row = src + (static_cast<size_t>(c.bounds[y * 2] + k)) * stride;
            const int32_t w = c.weights[static_cast<size_t>(y) * c.size + k];
            for (size_t i = 0; i < stride; ++i) sums[i] += static_cast<int32_t>(row[i]) * w;
        }
        for (size_t i = 0; i < stride; ++i) out[static_cast<size_t>(y) * stride + i] = clip_channel(sums[i]);
    }
    return out;
}

std::vector<uint8_t> resize_bicubic(const uint8_t * src, int width, int height, int out_width, int out_height) {
    if (out_width == width && out_height == height) {
        return std::vector<uint8_t>(src, src + static_cast<size_t>(width) * height * 3);
    }
    if (out_width != width && out_height != height) {
        auto pass = resize_horizontal(src, width, height, out_width);
        return resize_vertical(pass.data(), height, out_width, out_height);
    }
    if (out_width != width) return resize_horizontal(src, width, height, out_width);
    return resize_vertical(src, height, width, out_height);
}

struct Handle {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * media = nullptr;
    llama_batch_ext * batch = nullptr;
    const llama_vocab * vocab = nullptr;
    int embedding_size = 0;
    int row_size = 0;
    int max_tokens = 0;
    int sequences = 0;
    int tokens = 0;
    llama_pos position = 0;
    std::mutex mutex;

    ~Handle() { close(); }

    void close() {
        if (batch != nullptr) { llama_batch_ext_free(batch); batch = nullptr; }
        if (media != nullptr) { mtmd_free(media); media = nullptr; }
        if (context != nullptr) { llama_free(context); context = nullptr; }
        if (model != nullptr) { llama_model_free(model); model = nullptr; }
    }

    void reset() {
        llama_batch_ext_clear(batch);
        sequences = 0;
        tokens = 0;
    }

    void begin() {
        ++sequences;
        position = 0;
    }

    void place(int32_t index) {
        if (index < 0) throw std::runtime_error("batch full (" + std::to_string(index) + ")");
        llama_pos pos[4] = {position, 0, 0, 0};
        if (!llama_batch_ext_set_pos(batch, index, pos)) throw std::runtime_error("can't place token");
        if (!llama_batch_ext_set_output_embd(batch, index, true)) throw std::runtime_error("can't request output");
        ++position;
        ++tokens;
    }

    void add_token(llama_token token) {
        place(llama_batch_ext_add_token(batch, sequences - 1, token));
    }

    void add_rows(const float * rows, int count) {
        for (int row = 0; row < count; ++row) {
            llama_embd embd{rows + static_cast<size_t>(row) * row_size, 1, static_cast<size_t>(row_size)};
            place(llama_batch_ext_add_embd(batch, sequences - 1, embd));
        }
    }

    std::vector<std::vector<float>> run() {
        const int32_t status = llama_process(context, LLAMA_PROCESS_TYPE_ENCODE, batch);
        std::vector<std::vector<float>> out;
        if (status != 0) {
            reset();
            throw std::runtime_error("llama_process failed (" + std::to_string(status) + ")");
        }
        out.reserve(static_cast<size_t>(sequences));
        for (int seq = 0; seq < sequences; ++seq) {
            const float * pooled = llama_get_embeddings_seq(context, seq);
            if (pooled == nullptr) {
                reset();
                throw std::runtime_error("no pooled embedding for input " + std::to_string(seq));
            }
            double sum = 0.0;
            for (int i = 0; i < embedding_size; ++i) {
                sum += static_cast<double>(pooled[i]) * pooled[i];
            }
            const float scale = sum > 0.0 ? static_cast<float>(1.0 / std::sqrt(sum)) : 0.0f;
            std::vector<float> vector(static_cast<size_t>(embedding_size));
            for (int i = 0; i < embedding_size; ++i) vector[i] = pooled[i] * scale;
            out.push_back(std::move(vector));
        }
        reset();
        return out;
    }

    std::vector<llama_token> tokenize(const std::string & text) {
        std::vector<llama_token> tokens(text.size() + 16, 0);
        int32_t count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                       tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
        if (count < 0) {
            tokens.resize(static_cast<size_t>(-count));
            count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
        }
        if (count < 0) throw std::runtime_error("can't tokenize text");
        tokens.resize(static_cast<size_t>(count));
        if (static_cast<int>(tokens.size()) > max_tokens) {
            tokens.resize(static_cast<size_t>(max_tokens - 1));
            tokens.push_back(llama_vocab_eos(vocab));
        }
        return tokens;
    }

    std::vector<std::vector<float>> embed_texts(const std::vector<std::string> & texts) {
        reset();
        std::vector<std::vector<float>> vectors;
        for (const auto & text : texts) {
            const auto tokens = tokenize(text);

            if (sequences == kMaxSequences || this->tokens + static_cast<int>(tokens.size()) > max_tokens) {
                auto batch_out = run();
                vectors.insert(vectors.end(), batch_out.begin(), batch_out.end());
            }
            begin();
            for (auto token : tokens) add_token(token);
        }
        if (sequences > 0) {
            auto batch_out = run();
            vectors.insert(vectors.end(), batch_out.begin(), batch_out.end());
        }
        return vectors;
    }

    std::vector<float> embed_bitmap(const uint8_t * rgb, int width, int height, int budget) {
        ImageSize target{width, height};
        if (!target_size(height, width, budget, &target)) throw std::runtime_error("image too thin to embed");
        std::vector<uint8_t> resized;
        const uint8_t * pixels = rgb;
        if (target.width != width || target.height != height) {
            resized = resize_bicubic(rgb, width, height, target.width, target.height);
            pixels = resized.data();
        }
        mtmd_bitmap * bitmap = mtmd_bitmap_init(static_cast<uint32_t>(target.width),
                                                static_cast<uint32_t>(target.height), pixels);
        if (bitmap == nullptr) throw std::runtime_error("can't create bitmap");
        try {
            auto vector = embed_media(bitmap);
            mtmd_bitmap_free(bitmap);
            return vector;
        } catch (...) {
            mtmd_bitmap_free(bitmap);
            throw;
        }
    }

    std::vector<float> embed_audio(const float * samples, size_t count) {
        int rate = media != nullptr ? mtmd_get_audio_sample_rate(media) : 16000;
        if (rate <= 0) rate = 16000;
        const size_t limit = static_cast<size_t>(rate) * kMaxAudioSeconds;
        if (count > limit) count = limit;
        if (count == 0) throw std::runtime_error("empty audio");
        mtmd_bitmap * bitmap = mtmd_bitmap_init_from_audio(count, samples);
        if (bitmap == nullptr) throw std::runtime_error("can't create audio bitmap");
        try {
            auto vector = embed_media(bitmap);
            mtmd_bitmap_free(bitmap);
            return vector;
        } catch (...) {
            mtmd_bitmap_free(bitmap);
            throw;
        }
    }

    std::vector<float> embed_media(mtmd_bitmap * bitmap) {
        if (media == nullptr) throw std::runtime_error("this embedder was started text-only");
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        if (chunks == nullptr) throw std::runtime_error("can't create input chunks");
        const char * marker = mtmd_default_marker();
        mtmd_input_text text{marker, std::strlen(marker), true, true};
        const mtmd_bitmap * bitmaps[1] = {bitmap};
        const int32_t status = mtmd_tokenize(media, chunks, &text, bitmaps, 1);
        if (status != 0) {
            mtmd_input_chunks_free(chunks);
            throw std::runtime_error("can't tokenize media (" + std::to_string(status) + ")");
        }
        try {
            reset();
            begin();
            const size_t chunk_count = mtmd_input_chunks_size(chunks);
            for (size_t index = 0; index < chunk_count; ++index) {
                const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, index);
                if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                    size_t count = 0;
                    const llama_token * tokens = mtmd_input_chunk_get_tokens_text(chunk, &count);
                    if (tokens == nullptr) continue;
                    for (size_t i = 0; i < count; ++i) add_token(tokens[i]);
                } else {
                    if (mtmd_encode_chunk(media, chunk) != 0) throw std::runtime_error("the media encoder failed");
                    const float * rows = mtmd_get_output_embd(media);
                    if (rows == nullptr) throw std::runtime_error("the media encoder returned nothing");
                    add_rows(rows, static_cast<int>(mtmd_input_chunk_get_n_tokens(chunk)));
                }
            }
            auto vectors = run();
            mtmd_input_chunks_free(chunks);
            if (vectors.empty()) throw std::runtime_error("no vector");
            return vectors[0];
        } catch (...) {
            mtmd_input_chunks_free(chunks);
            throw;
        }
    }
};

Handle * handle_from(jlong value) {
    if (value == 0) throw std::runtime_error("embedder is closed");
    return reinterpret_cast<Handle *>(value);
}

jfloatArray to_float_array(JNIEnv * env, const std::vector<float> & vector) {
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(vector.size()));
    if (array == nullptr) return nullptr;
    env->SetFloatArrayRegion(array, 0, static_cast<jsize>(vector.size()), vector.data());
    return array;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_digup_android_ml_LlamaBridge_nativeInit(JNIEnv * env, jclass, jstring text_path, jstring media_path,
                                                 jint threads, jboolean text_only) {
    try {
        static std::once_flag backend_once;
        std::call_once(backend_once, [] {
            llama_log_set(log_callback, nullptr);
            mtmd_helper_log_set(log_callback, nullptr);
            llama_backend_init();
        });

        const std::string text_file = java_string(env, text_path);
        const std::string media_file = java_string(env, media_path);
        const bool textOnly = text_only == JNI_TRUE;

        auto * handle = new Handle();
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        handle->model = llama_model_load_from_file(text_file.c_str(), model_params);
        if (handle->model == nullptr) {
            delete handle;
            throw std::runtime_error("can't load " + text_file);
        }

        const int max_tokens = textOnly ? 512 : 2048;
        llama_context_params params = llama_context_default_params();
        params.n_ctx = static_cast<uint32_t>(max_tokens);
        params.n_batch = static_cast<uint32_t>(max_tokens);
        params.n_ubatch = static_cast<uint32_t>(max_tokens);
        params.n_seq_max = kMaxSequences;
        params.kv_unified = true;
        params.embeddings = true;
        params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        params.n_threads = threads > 0 ? threads : 4;
        params.n_threads_batch = params.n_threads;
        handle->context = llama_init_from_model(handle->model, params);
        if (handle->context == nullptr) {
            delete handle;
            throw std::runtime_error("can't create a llama.cpp context");
        }

        if (!textOnly && !media_file.empty()) {
            mtmd_context_params media_params = mtmd_context_params_default();
            media_params.use_gpu = false;
            media_params.print_timings = false;
            media_params.warmup = true;
            media_params.image_min_tokens = 1;
            media_params.image_max_tokens = 1120;
            media_params.n_threads = params.n_threads;
            handle->media = mtmd_init_from_file(media_file.c_str(), handle->model, media_params);
            if (handle->media == nullptr) {
                delete handle;
                throw std::runtime_error("can't load " + media_file);
            }
        }

        handle->batch = llama_batch_ext_init(handle->context);
        if (handle->batch == nullptr) {
            delete handle;
            throw std::runtime_error("can't create a batch");
        }
        handle->vocab = llama_model_get_vocab(handle->model);
        handle->embedding_size = llama_model_n_embd_out(handle->model);
        handle->row_size = llama_model_n_embd_inp(handle->model);
        handle->max_tokens = max_tokens;

        // Pay the first-call setup now, as the iOS runtime does.
        {
            std::lock_guard<std::mutex> lock(handle->mutex);
            handle->embed_texts({"warm up"});
            handle->embed_texts({"warm up"});
        }
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_digup_android_ml_LlamaBridge_nativeClose(JNIEnv *, jclass, jlong pointer) {
    delete reinterpret_cast<Handle *>(pointer);
}

JNIEXPORT jint JNICALL
Java_com_digup_android_ml_LlamaBridge_nativeAudioSampleRate(JNIEnv *, jclass, jlong pointer) {
    try {
        Handle * handle = handle_from(pointer);
        std::lock_guard<std::mutex> lock(handle->mutex);
        if (handle->media == nullptr) return 0;
        const int rate = mtmd_get_audio_sample_rate(handle->media);
        return rate > 0 ? rate : 16000;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_digup_android_ml_LlamaBridge_nativeEmbedTexts(JNIEnv * env, jclass, jlong pointer, jobjectArray texts) {
    try {
        Handle * handle = handle_from(pointer);
        const jsize count = env->GetArrayLength(texts);
        std::vector<std::string> inputs;
        inputs.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto string = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
            inputs.push_back(java_string(env, string));
            env->DeleteLocalRef(string);
        }

        std::vector<std::vector<float>> vectors;
        {
            std::lock_guard<std::mutex> lock(handle->mutex);
            vectors = handle->embed_texts(inputs);
        }
        jclass float_class = env->FindClass("[F");
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(vectors.size()), float_class, nullptr);
        for (size_t i = 0; i < vectors.size(); ++i) {
            jfloatArray array = to_float_array(env, vectors[i]);
            env->SetObjectArrayElement(result, static_cast<jsize>(i), array);
            env->DeleteLocalRef(array);
        }
        return result;
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_digup_android_ml_LlamaBridge_nativeEmbedBitmap(JNIEnv * env, jclass, jlong pointer,
                                                        jobject bitmap, jint budget) {
    try {
        Handle * handle = handle_from(pointer);
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throw std::runtime_error("can't read the bitmap");
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            throw std::runtime_error("bitmap must be ARGB_8888");
        }
        void * pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            throw std::runtime_error("can't lock the bitmap");
        }
        const int width = static_cast<int>(info.width);
        const int height = static_cast<int>(info.height);
        std::vector<uint8_t> rgb(static_cast<size_t>(width) * height * 3);
        const auto * source = static_cast<const uint8_t *>(pixels);
        for (int y = 0; y < height; ++y) {
            const uint8_t * row = source + static_cast<size_t>(y) * info.stride;
            for (int x = 0; x < width; ++x) {
                const uint8_t * p = row + static_cast<size_t>(x) * 4;
                const size_t o = (static_cast<size_t>(y) * width + x) * 3;
                rgb[o] = p[0];
                rgb[o + 1] = p[1];
                rgb[o + 2] = p[2];
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);

        std::vector<float> vector;
        {
            std::lock_guard<std::mutex> lock(handle->mutex);
            vector = handle->embed_bitmap(rgb.data(), width, height, budget);
        }
        return to_float_array(env, vector);
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_digup_android_ml_LlamaBridge_nativeEmbedAudio(JNIEnv * env, jclass, jlong pointer, jfloatArray samples) {
    try {
        Handle * handle = handle_from(pointer);
        const jsize count = env->GetArrayLength(samples);
        std::vector<float> values(static_cast<size_t>(count));
        env->GetFloatArrayRegion(samples, 0, count, values.data());
        std::vector<float> vector;
        {
            std::lock_guard<std::mutex> lock(handle->mutex);
            vector = handle->embed_audio(values.data(), values.size());
        }
        return to_float_array(env, vector);
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

} // extern "C"


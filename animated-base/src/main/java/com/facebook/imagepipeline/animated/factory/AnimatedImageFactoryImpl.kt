/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.animated.factory

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.facebook.common.references.CloseableReference
import com.facebook.imagepipeline.animated.base.AnimatedImage
import com.facebook.imagepipeline.animated.base.AnimatedImageResult
import com.facebook.imagepipeline.animated.impl.AnimatedDrawableBackendProvider
import com.facebook.imagepipeline.animated.impl.AnimatedImageCompositor
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.image.CloseableAnimatedImage
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.image.ImmutableQualityInfo

/** Decoder for animated images. */
class AnimatedImageFactoryImpl
@JvmOverloads
constructor(
    private val animatedDrawableBackendProvider: AnimatedDrawableBackendProvider,
    private val bitmapFactory: PlatformBitmapFactory,
    private val isNewRenderImplementation: Boolean,
    private val treatAnimatedImagesAsStateful: Boolean = true
) : AnimatedImageFactory {

  /**
   * Decodes a GIF into a CloseableImage.
   *
   * @param encodedImage encoded image (native byte array holding the encoded bytes and meta data)
   * @param options the options for the decode
   * @param bitmapConfig the Bitmap.Config used to generate the output bitmaps
   * @return a [CloseableImage] for the GIF image
   */
  override fun decodeGif(
      encodedImage: EncodedImage,
      options: ImageDecodeOptions,
      bitmapConfig: Bitmap.Config
  ): CloseableImage {
    val decoder =
        gifAnimatedImageDecoder
            ?: throw UnsupportedOperationException(
                "To encode animated gif please add the dependency to the animated-gif module")
    return decodeImage(encodedImage, options, bitmapConfig, decoder, GifImageValidator)
  }

  /**
   * Decode a WebP into a CloseableImage.
   *
   * @param encodedImage encoded image (native byte array holding the encoded bytes and meta data)
   * @param options the options for the decode
   * @param bitmapConfig the Bitmap.Config used to generate the output bitmaps
   * @return a [CloseableImage] for the WebP image
   */
  override fun decodeWebP(
      encodedImage: EncodedImage,
      options: ImageDecodeOptions,
      bitmapConfig: Bitmap.Config
  ): CloseableImage {
    val decoder =
        webpAnimatedImageDecoder
            ?: throw UnsupportedOperationException(
                "To encode animated webp please add the dependency to the animated-webp module")
    return decodeImage(encodedImage, options, bitmapConfig, decoder)
  }

  private fun decodeImage(
      encodedImage: EncodedImage,
      options: ImageDecodeOptions,
      bitmapConfig: Bitmap.Config,
      decoder: AnimatedImageDecoder,
      validator: AnimatedImageValidator? = null
  ): CloseableImage {
    val bytesRef = encodedImage.byteBufferRef
    checkNotNull(bytesRef)

    bytesRef.use {
      val validationResult = validator?.validateImage(encodedImage)
      if (validationResult?.isValid == false) {
        Log.w("AnimatedImageFactory", "GIF validation failed: ${validationResult.message}")
        throw UnsupportedOperationException("Invalid GIF: ${validationResult.message}")
      }
      val input = bytesRef.get()
      val image =
          input.byteBuffer?.let { decoder.decodeFromByteBuffer(it, options) }
              ?: decoder.decodeFromNativeMemory(input.nativePtr, input.size(), options)

      return getCloseableImage(encodedImage.source, options, checkNotNull(image), bitmapConfig)
    }
  }

  private fun getCloseableImage(
      sourceUri: String?,
      options: ImageDecodeOptions,
      image: AnimatedImage,
      bitmapConfig: Bitmap.Config
  ): CloseableImage {
    var decodedFrames: List<CloseableReference<Bitmap>?>? = null
    var previewBitmap: CloseableReference<Bitmap>? = null
    try {
      val frameForPreview = if (options.useLastFrameForPreview) (image.frameCount - 1) else 0
      if (options.forceStaticImage) {
        return CloseableStaticBitmap.of(
            createPreviewBitmap(image, bitmapConfig, frameForPreview),
            ImmutableQualityInfo.FULL_QUALITY,
            0)
      }

      if (options.decodeAllFrames) {
        decodedFrames = decodeAllFrames(image, bitmapConfig)
        previewBitmap = CloseableReference.cloneOrNull(decodedFrames[frameForPreview])
      }

      if (options.decodePreviewFrame && previewBitmap == null) {
        previewBitmap = createPreviewBitmap(image, bitmapConfig, frameForPreview)
      }
      val animatedImageResult =
          AnimatedImageResult.newBuilder(image)
              .setPreviewBitmap(previewBitmap)
              .setFrameForPreview(frameForPreview)
              .setDecodedFrames(decodedFrames)
              .setBitmapTransformation(options.bitmapTransformation)
              .setSource(sourceUri)
              .build()
      return CloseableAnimatedImage(animatedImageResult, treatAnimatedImagesAsStateful)
    } finally {
      CloseableReference.closeSafely(previewBitmap)
      CloseableReference.closeSafely(decodedFrames)
    }
  }

  private fun createPreviewBitmap(
      image: AnimatedImage,
      bitmapConfig: Bitmap.Config,
      frameForPreview: Int
  ): CloseableReference<Bitmap> {
    val bitmap = createBitmap(image.width, image.height, bitmapConfig)
    val tempResult = AnimatedImageResult.forAnimatedImage(image)
    val drawableBackend = animatedDrawableBackendProvider.get(tempResult, null)
    val animatedImageCompositor =
        AnimatedImageCompositor(
            drawableBackend,
            isNewRenderImplementation,
            object : AnimatedImageCompositor.Callback {
              override fun onIntermediateResult(frameNumber: Int, bitmap: Bitmap) {
                // Don't care.
              }

              override fun getCachedBitmap(frameNumber: Int): CloseableReference<Bitmap>? = null
            })
    animatedImageCompositor.renderFrame(frameForPreview, bitmap.get())
    return bitmap
  }

  private fun decodeAllFrames(
      image: AnimatedImage,
      bitmapConfig: Bitmap.Config
  ): List<CloseableReference<Bitmap>?> {
    val tempResult = AnimatedImageResult.forAnimatedImage(image)
    val drawableBackend = animatedDrawableBackendProvider.get(tempResult, null)
    val bitmaps: MutableList<CloseableReference<Bitmap>?> = ArrayList(drawableBackend.frameCount)
    val animatedImageCompositor =
        AnimatedImageCompositor(
            drawableBackend,
            isNewRenderImplementation,
            object : AnimatedImageCompositor.Callback {
              override fun onIntermediateResult(frameNumber: Int, bitmap: Bitmap) {
                // Don't care.
              }

              override fun getCachedBitmap(frameNumber: Int): CloseableReference<Bitmap>? =
                  CloseableReference.cloneOrNull(bitmaps[frameNumber])
            })
    for (i in 0..<drawableBackend.frameCount) {
      val bitmap = createBitmap(drawableBackend.width, drawableBackend.height, bitmapConfig)
      animatedImageCompositor.renderFrame(i, bitmap.get())
      bitmaps.add(bitmap)
    }
    return bitmaps
  }

  @SuppressLint("NewApi")
  private fun createBitmap(
      width: Int,
      height: Int,
      bitmapConfig: Bitmap.Config
  ): CloseableReference<Bitmap> {
    val bitmap = bitmapFactory.createBitmapInternal(width, height, bitmapConfig)
    bitmap.get().eraseColor(Color.TRANSPARENT)
    bitmap.get().setHasAlpha(true)
    return bitmap
  }

  companion object {
    @VisibleForTesting @JvmField var gifAnimatedImageDecoder: AnimatedImageDecoder? = null

    @VisibleForTesting @JvmField var webpAnimatedImageDecoder: AnimatedImageDecoder? = null

    private fun loadIfPresent(className: String): AnimatedImageDecoder? {
      try {
        val clazz = Class.forName(className)
        return clazz.newInstance() as AnimatedImageDecoder
      } catch (e: Throwable) {
        return null
      }
    }

    init {
      gifAnimatedImageDecoder = loadIfPresent("com.facebook.animated.gif.GifImage")
      webpAnimatedImageDecoder = loadIfPresent("com.facebook.animated.webp.WebPImage")
    }
  }
}

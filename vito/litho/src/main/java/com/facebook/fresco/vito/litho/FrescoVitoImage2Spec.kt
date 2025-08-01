/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.litho

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.core.util.ObjectsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.facebook.common.callercontext.ContextChain
import com.facebook.datasource.DataSource
import com.facebook.drawee.drawable.Viewport
import com.facebook.fresco.middleware.HasExtraData
import com.facebook.fresco.ui.common.OnFadeListener
import com.facebook.fresco.urimod.ClassicFetchStrategy
import com.facebook.fresco.urimod.FetchStrategy
import com.facebook.fresco.urimod.NoPrefetchInOnPrepareStrategy
import com.facebook.fresco.urimod.SmartFetchStrategy
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.fresco.vito.litho.FrescoVitoImage2Spec.Prefetch.AUTO
import com.facebook.fresco.vito.litho.FrescoVitoImage2Spec.Prefetch.NO
import com.facebook.fresco.vito.litho.FrescoVitoImage2Spec.Prefetch.YES
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.provider.FrescoVitoProvider
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.imagepipeline.listener.RequestListener
import com.facebook.litho.AccessibilityRole
import com.facebook.litho.ComponentContext
import com.facebook.litho.ComponentLayout
import com.facebook.litho.ContextUtils
import com.facebook.litho.Diff
import com.facebook.litho.Output
import com.facebook.litho.Ref
import com.facebook.litho.Size
import com.facebook.litho.StateValue
import com.facebook.litho.annotations.CachedValue
import com.facebook.litho.annotations.ExcuseMySpec
import com.facebook.litho.annotations.FromBoundsDefined
import com.facebook.litho.annotations.FromPrepare
import com.facebook.litho.annotations.MountSpec
import com.facebook.litho.annotations.MountingType
import com.facebook.litho.annotations.OnBind
import com.facebook.litho.annotations.OnBoundsDefined
import com.facebook.litho.annotations.OnCalculateCachedValue
import com.facebook.litho.annotations.OnCreateInitialState
import com.facebook.litho.annotations.OnCreateMountContent
import com.facebook.litho.annotations.OnCreateMountContentPool
import com.facebook.litho.annotations.OnDetached
import com.facebook.litho.annotations.OnMeasure
import com.facebook.litho.annotations.OnMount
import com.facebook.litho.annotations.OnPopulateAccessibilityNode
import com.facebook.litho.annotations.OnPrepare
import com.facebook.litho.annotations.OnUnbind
import com.facebook.litho.annotations.OnUnmount
import com.facebook.litho.annotations.Prop
import com.facebook.litho.annotations.PropDefault
import com.facebook.litho.annotations.Reason
import com.facebook.litho.annotations.ResType
import com.facebook.litho.annotations.ShouldExcludeFromIncrementalMount
import com.facebook.litho.annotations.ShouldUpdate
import com.facebook.litho.annotations.State
import com.facebook.litho.annotations.TreeProp
import com.facebook.litho.utils.MeasureUtils
import com.facebook.rendercore.MountContentPools

/** Fresco Vito component for Litho */
@ExcuseMySpec(reason = Reason.SECTION_USED_WITH_OTHER_SECTIONS)
@MountSpec(isPureRender = true, canPreallocate = true)
object FrescoVitoImage2Spec {

  private const val DEFAULT_IMAGE_ASPECT_RATIO = 1f

  @PropDefault const val imageAspectRatio: Float = DEFAULT_IMAGE_ASPECT_RATIO

  @PropDefault val prefetch: Prefetch = Prefetch.AUTO

  @PropDefault const val mutateDrawables: Boolean = true

  @JvmStatic
  @OnCreateMountContentPool
  fun onCreateMountContentPool(poolsize: Int): MountContentPools.ContentPool =
      MountContentPools.DefaultContentPool(
          FrescoVitoImage2Spec::class.java,
          FrescoVitoProvider.getConfig().experimentalPoolSizeVito2().toInt())

  @JvmStatic
  @OnCreateMountContent(mountingType = MountingType.DRAWABLE)
  fun onCreateMountContent(c: Context?): FrescoDrawableInterface =
      FrescoVitoProvider.getController().createDrawable("litho")

  @JvmStatic
  @OnCreateInitialState
  fun onCreateInitialState(frescoDrawableRef: StateValue<Ref<FrescoDrawableInterface?>?>) {
    if (FrescoVitoProvider.getConfig().useDetached()) {
      frescoDrawableRef.set(Ref<FrescoDrawableInterface?>(null))
    }
  }

  @JvmStatic
  @OnMeasure
  fun onMeasure(
      c: ComponentContext,
      layout: ComponentLayout,
      widthSpec: Int,
      heightSpec: Int,
      size: Size,
      @Prop(optional = true, resType = ResType.FLOAT) imageAspectRatio: Float,
  ) {
    val resolvedAspectRatio: Float =
        if (!(imageAspectRatio > 0f)) {
          // If the image aspect ratio is not set correctly, we will use the default aspect ratio of
          // 1.0f, we've seen bad inputs like 0.0f and NaN.
          DEFAULT_IMAGE_ASPECT_RATIO
        } else {
          imageAspectRatio
        }
    MeasureUtils.measureWithAspectRatio(widthSpec, heightSpec, resolvedAspectRatio, size)
  }

  @JvmStatic
  @OnCalculateCachedValue(name = "requestBeforeLayout")
  fun onCalculateImageRequest(
      c: ComponentContext,
      @Prop(optional = true) callerContext: Any?,
      @Prop(optional = true) uriString: String?,
      @Prop(optional = true) uri: Uri?,
      @Prop(optional = true) imageSource: ImageSource?,
      @Prop(optional = true) imageOptions: ImageOptions?,
      @Prop(optional = true) logWithHighSamplingRate: Boolean?,
  ): VitoImageRequest? {
    val shouldCreateRequest =
        !experimentalDynamicSizeVito2() || experimentalDynamicSizeWithCacheFallbackVito2()
    if (!shouldCreateRequest) {
      return null
    }
    val imageOptions = ensureImageOptions(imageOptions)
    return createVitoImageRequest(
        c,
        callerContext,
        imageSource,
        uri,
        uriString,
        imageOptions,
        logWithHighSamplingRate,
        viewportRect = null,
        fetchStrategy = null)
  }

  private fun createVitoImageRequest(
      c: ComponentContext,
      callerContext: Any?,
      imageSource: ImageSource?,
      uri: Uri?,
      uriString: String?,
      imageOptions: ImageOptions?,
      logWithHighSamplingRate: Boolean?,
      viewportRect: Rect?,
      fetchStrategy: FetchStrategy?,
  ): VitoImageRequest =
      FrescoVitoProvider.getImagePipeline()
          .createImageRequest(
              c.resources,
              determineImageSource(imageSource, uri, uriString),
              imageOptions,
              logWithHighSamplingRate ?: false,
              viewportRect,
              callerContext,
              null,
              fetchStrategy)

  @JvmStatic
  @OnPrepare
  fun onPrepare(
      c: ComponentContext,
      @Prop(optional = true) callerContext: Any?,
      @TreeProp contextChain: ContextChain?,
      @TreeProp viewport: Viewport?,
      @Prop(optional = true) uriString: String?,
      @Prop(optional = true) uri: Uri?,
      @Prop(optional = true) imageSource: ImageSource?,
      @Prop(optional = true) imageOptions: ImageOptions?,
      @Prop(optional = true) logWithHighSamplingRate: Boolean?,
      @Prop(optional = true) prefetch: Prefetch?,
      @Prop(optional = true) prefetchRequestListener: RequestListener?,
      @CachedValue requestBeforeLayout: VitoImageRequest?,
      prefetchDataSource: Output<DataSource<Void?>>,
      fetchStrategy: Output<FetchStrategy>,
  ) {
    val result =
        FrescoVitoProvider.getImagePipeline()
            .determineFetchStrategy(requestBeforeLayout, callerContext, contextChain)
    when (result) {
      is ClassicFetchStrategy -> {
        checkNotNull(requestBeforeLayout)
        maybePrefetchInOnPrepare(
            prefetch,
            prefetchDataSource,
            requestBeforeLayout,
            callerContext,
            contextChain,
            prefetchRequestListener)
      }
      is SmartFetchStrategy -> {
        if (viewport != null) {
          val viewportAwareImageRequest =
              createVitoImageRequest(
                  c,
                  callerContext,
                  imageSource,
                  uri,
                  uriString,
                  imageOptions,
                  logWithHighSamplingRate,
                  viewport.toRect(),
                  result)
          maybePrefetchInOnPrepare(
              prefetch,
              prefetchDataSource,
              viewportAwareImageRequest,
              callerContext,
              contextChain,
              prefetchRequestListener)
        }
      }
      NoPrefetchInOnPrepareStrategy -> {}
    }
    fetchStrategy.set(result)
  }

  @JvmStatic
  @OnMount
  fun onMount(
      c: ComponentContext,
      frescoDrawable: FrescoDrawableInterface,
      @Prop(optional = true) imageListener: ImageListener?,
      @Prop(optional = true) callerContext: Any?,
      @Prop(optional = true) onFadeListener: OnFadeListener?,
      @Prop(optional = true) mutateDrawables: Boolean,
      @CachedValue requestBeforeLayout: VitoImageRequest?,
      @FromBoundsDefined requestWithLayout: VitoImageRequest?,
      @FromPrepare prefetchDataSource: DataSource<Void?>?,
      @FromBoundsDefined prefetchDataSourceFromBoundsDefined: DataSource<Void?>?,
      @FromBoundsDefined viewportDimensions: Rect,
      @TreeProp contextChain: ContextChain?,
      @FromPrepare fetchStrategy: FetchStrategy,
      @State frescoDrawableRef: Ref<FrescoDrawableInterface?>?,
  ) {
    if (frescoDrawableRef != null && FrescoVitoProvider.getConfig().useDetached()) {
      frescoDrawableRef.value = frescoDrawable
    }
    if (FrescoVitoProvider.getConfig().useMount()) {
      val request =
          when {
            requestWithLayout != null -> requestWithLayout
            requestBeforeLayout != null -> {
              val request =
                  VitoImageRequest(
                      requestBeforeLayout.resources,
                      requestBeforeLayout.imageSource,
                      requestBeforeLayout.imageOptions,
                      requestBeforeLayout.logWithHighSamplingRate,
                      requestBeforeLayout.finalImageRequest,
                      requestBeforeLayout.finalImageCacheKey,
                      requestBeforeLayout.extras,
                  )
              request.putExtra(HasExtraData.KEY_SF_FETCH_STRATEGY, fetchStrategy)
              request
            }

            else -> error("requestWithLayout and requestBeforeLayout are null")
          }

      frescoDrawable.setMutateDrawables(mutateDrawables)
      if (FrescoVitoProvider.getConfig().useBindOnly()) {
        return
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
          FrescoVitoProvider.getConfig().enableWindowWideColorGamut()) {
        val activity: Activity? = ContextUtils.findActivityInContext(c.androidContext)
        val window = activity?.window
        if (window != null && window.colorMode != ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT) {
          window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
      }

      FrescoVitoProvider.getController()
          .fetch(
              drawable = frescoDrawable,
              imageRequest = request,
              callerContext = callerContext,
              contextChain = contextChain,
              listener = imageListener,
              onFadeListener = onFadeListener,
              viewportDimensions = viewportDimensions)
      frescoDrawable.imagePerfListener.onImageMount(frescoDrawable)
    }
    if (shouldClosePrefetchDataSourceOnBindOrOnMount()) {
      prefetchDataSource?.close()
      prefetchDataSourceFromBoundsDefined?.close()
    }
  }

  @JvmStatic
  @OnBind
  fun onBind(
      c: ComponentContext,
      frescoDrawable: FrescoDrawableInterface,
      @Prop(optional = true) imageListener: ImageListener?,
      @Prop(optional = true) onFadeListener: OnFadeListener?,
      @Prop(optional = true) callerContext: Any?,
      @TreeProp contextChain: ContextChain?,
      @CachedValue requestBeforeLayout: VitoImageRequest?,
      @FromBoundsDefined requestWithLayout: VitoImageRequest?,
      @FromPrepare prefetchDataSource: DataSource<Void?>?,
      @FromBoundsDefined prefetchDataSourceFromBoundsDefined: DataSource<Void?>?,
      @FromBoundsDefined viewportDimensions: Rect,
      @FromPrepare fetchStrategy: FetchStrategy,
  ) {
    if (FrescoVitoProvider.getConfig().useBind()) {
      // if requestWithLayout is not null, we are using SF
      val request = checkNotNull(requestWithLayout ?: requestBeforeLayout)
      request.putExtra(HasExtraData.KEY_SF_FETCH_STRATEGY, fetchStrategy)

      // We fetch in both mount and bind in case an unbind event triggered a delayed release.
      // We'll only trigger an actual fetch if needed. Most of the time, this will be a no-op.
      FrescoVitoProvider.getController()
          .fetch(
              drawable = frescoDrawable,
              imageRequest = request,
              callerContext = callerContext,
              contextChain = contextChain,
              listener = imageListener,
              onFadeListener = onFadeListener,
              viewportDimensions = viewportDimensions)
      frescoDrawable.imagePerfListener.onImageBind(frescoDrawable)
    }
    if (shouldClosePrefetchDataSourceOnBindOrOnMount()) {
      prefetchDataSource?.close()
      prefetchDataSourceFromBoundsDefined?.close()
    }
  }

  @JvmStatic
  @OnUnbind
  fun onUnbind(
      c: ComponentContext,
      frescoDrawable: FrescoDrawableInterface,
      @FromPrepare prefetchDataSource: DataSource<Void?>?,
      @FromBoundsDefined prefetchDataSourceFromBoundsDefined: DataSource<Void?>?,
  ) {
    if (FrescoVitoProvider.getConfig().useUnbind()) {
      frescoDrawable.imagePerfListener.onImageUnbind(frescoDrawable)
      if (FrescoVitoProvider.getConfig().useBindOnly()) {
        FrescoVitoProvider.getController().releaseImmediately(frescoDrawable)
      } else {
        FrescoVitoProvider.getController().releaseDelayed(frescoDrawable)
      }
    }
    prefetchDataSource?.close()
    prefetchDataSourceFromBoundsDefined?.close()
  }

  @JvmStatic
  @OnUnmount
  fun onUnmount(
      c: ComponentContext,
      frescoDrawable: FrescoDrawableInterface,
      @FromPrepare prefetchDataSource: DataSource<Void?>?,
      @FromBoundsDefined prefetchDataSourceFromBoundsDefined: DataSource<Void?>?,
  ) {
    if (FrescoVitoProvider.getConfig().useUnmount()) {
      frescoDrawable.imagePerfListener.onImageUnmount(frescoDrawable)
      if (FrescoVitoProvider.getConfig().useBindOnly()) {
        return
      }
      FrescoVitoProvider.getController().release(frescoDrawable)
    }
    prefetchDataSource?.close()
    prefetchDataSourceFromBoundsDefined?.close()
  }

  @JvmStatic
  @OnDetached
  fun onDetached(
      c: ComponentContext,
      @State frescoDrawableRef: Ref<FrescoDrawableInterface?>?,
  ) {
    if (FrescoVitoProvider.getConfig().useDetached()) {
      val drawable = frescoDrawableRef?.value
      if (drawable != null) {
        releaseDrawableWithMechanism(
            drawable,
            ReleaseStrategy.parse(FrescoVitoProvider.getConfig().onDetachedReleaseStrategy()))
        frescoDrawableRef.value = null
      }
    }
  }

  @JvmStatic
  @ShouldUpdate(onMount = true)
  fun shouldUpdate(
      @Prop(optional = true) uri: Diff<Uri>,
      @Prop(optional = true) imageSource: Diff<ImageSource>,
      @Prop(optional = true) imageOptions: Diff<ImageOptions>,
      @Prop(optional = true, resType = ResType.FLOAT) imageAspectRatio: Diff<Float>,
      @Prop(optional = true) imageListener: Diff<ImageListener>,
  ): Boolean =
      !ObjectsCompat.equals(uri.previous, uri.next) ||
          !ObjectsCompat.equals(imageSource.previous, imageSource.next) ||
          !ObjectsCompat.equals(imageOptions.previous, imageOptions.next) ||
          !ObjectsCompat.equals(imageAspectRatio.previous, imageAspectRatio.next) ||
          !ObjectsCompat.equals(imageListener.previous, imageListener.next)

  @JvmStatic
  @OnPopulateAccessibilityNode
  fun onPopulateAccessibilityNode(
      c: ComponentContext,
      host: View,
      node: AccessibilityNodeInfoCompat
  ) {
    node.className = AccessibilityRole.IMAGE
  }

  @JvmStatic
  @OnBoundsDefined
  fun onBoundsDefined(
      c: ComponentContext,
      layout: ComponentLayout,
      viewportDimensions: Output<Rect>,
      @TreeProp contextChain: ContextChain?,
      @TreeProp viewport: Viewport?,
      requestWithLayout: Output<VitoImageRequest>,
      prefetchDataSourceFromBoundsDefined: Output<DataSource<Void?>>,
      @Prop(optional = true) prefetch: Prefetch?,
      @Prop(optional = true) uriString: String?,
      @Prop(optional = true) uri: Uri?,
      @Prop(optional = true) imageSource: ImageSource?,
      @Prop(optional = true) imageOptions: ImageOptions?,
      @Prop(optional = true) callerContext: Any?,
      @Prop(optional = true) logWithHighSamplingRate: Boolean?,
      @Prop(optional = true) prefetchRequestListener: RequestListener?,
      @FromPrepare fetchStrategy: FetchStrategy,
  ) {
    val imageOptions = ensureImageOptions(imageOptions)
    val width = layout.width
    val height = layout.height
    var paddingX = 0
    var paddingY = 0
    if (layout.isPaddingSet) {
      paddingX = layout.paddingLeft + layout.paddingRight
      paddingY = layout.paddingTop + layout.paddingBottom
    }
    val viewportRect = Rect(0, 0, width - paddingX, height - paddingY)
    viewportDimensions.set(viewportRect)

    when (fetchStrategy) {
      is SmartFetchStrategy -> {
        val vitoImageRequest =
            createVitoImageRequest(
                c,
                callerContext,
                imageSource,
                uri,
                uriString,
                imageOptions,
                logWithHighSamplingRate,
                viewportRect,
                fetchStrategy)

        requestWithLayout.set(vitoImageRequest)

        val config = FrescoVitoProvider.getConfig().prefetchConfig
        // Skip prefetch if viewport available, since it already happened in OnPrepare
        if (shouldPrefetchInBoundsDefinedForDynamicSize(prefetch) && viewport == null) {
          prefetchDataSourceFromBoundsDefined.set(
              FrescoVitoProvider.getPrefetcher()
                  .prefetch(
                      config.prefetchTargetOnBoundsDefined(),
                      vitoImageRequest,
                      callerContext,
                      contextChain,
                      prefetchRequestListener,
                      "FrescoVitoImage2Spec_OnBoundsDefined"))
        }
      }

      is ClassicFetchStrategy -> {
        /* no-op */
      }

      is NoPrefetchInOnPrepareStrategy -> {}
    }
  }

  @JvmStatic
  @ShouldExcludeFromIncrementalMount
  fun shouldExcludeFromIM(@Prop(optional = true) shouldExcludeFromIM: Boolean): Boolean =
      shouldExcludeFromIM

  private fun determineImageSource(
      imageSource: ImageSource?,
      uri: Uri?,
      uriString: String?,
  ): ImageSource =
      when {
        imageSource != null -> imageSource
        uri != null -> ImageSourceProvider.forUri(uri)
        uriString != null -> ImageSourceProvider.forUri(uriString)
        else -> ImageSourceProvider.emptySource()
      }

  private fun maybePrefetchInOnPrepare(
      prefetch: Prefetch?,
      prefetchDataSource: Output<DataSource<Void?>>,
      requestBeforeLayout: VitoImageRequest,
      callerContext: Any?,
      contextChain: ContextChain?,
      prefetchRequestListener: RequestListener?
  ) {
    val config = FrescoVitoProvider.getConfig().prefetchConfig
    if (shouldPrefetchInOnPrepare(prefetch)) {
      prefetchDataSource.set(
          FrescoVitoProvider.getPrefetcher()
              .prefetch(
                  config.prefetchTargetOnPrepare(),
                  requestBeforeLayout,
                  callerContext,
                  contextChain,
                  prefetchRequestListener,
                  "FrescoVitoImage2Spec_OnPrepare"))
    }
  }

  @JvmStatic
  fun shouldPrefetchInOnPrepare(prefetch: Prefetch?): Boolean =
      when (prefetch ?: Prefetch.AUTO) {
        Prefetch.YES -> true
        Prefetch.NO -> false
        else -> FrescoVitoProvider.getConfig().prefetchConfig.prefetchInOnPrepare()
      }

  @JvmStatic
  fun shouldPrefetchInBoundsDefinedForDynamicSize(prefetch: Prefetch?): Boolean =
      when (prefetch ?: Prefetch.AUTO) {
        Prefetch.YES ->
            FrescoVitoProvider.getConfig().prefetchConfig.prefetchInOnBoundsDefinedForDynamicSize()

        Prefetch.NO -> false
        else ->
            FrescoVitoProvider.getConfig().prefetchConfig.prefetchInOnBoundsDefinedForDynamicSize()
      }

  @JvmStatic
  fun shouldClosePrefetchDataSourceOnBindOrOnMount(): Boolean =
      FrescoVitoProvider.getConfig().prefetchConfig.closePrefetchDataSourceOnBindorOnMount()

  private fun ensureImageOptions(imageOptionsProp: ImageOptions?): ImageOptions? {
    if (imageOptionsProp == null &&
        FrescoVitoProvider.getConfig().fallbackToDefaultImageOptions()) {
      return ImageOptions.defaults()
    }
    return imageOptionsProp
  }

  private fun experimentalDynamicSizeVito2(): Boolean =
      FrescoVitoProvider.getConfig().experimentalDynamicSizeVito2()

  private fun experimentalDynamicSizeWithCacheFallbackVito2(): Boolean =
      FrescoVitoProvider.getConfig().experimentalDynamicSizeWithCacheFallbackVito2()

  private fun releaseDrawableWithMechanism(
      drawable: FrescoDrawableInterface,
      releaseStrategy: ReleaseStrategy
  ) {
    when (releaseStrategy) {
      ReleaseStrategy.IMMEDIATE -> FrescoVitoProvider.getController().releaseImmediately(drawable)
      ReleaseStrategy.DELAYED -> FrescoVitoProvider.getController().releaseDelayed(drawable)
      ReleaseStrategy.NEXT_FRAME -> FrescoVitoProvider.getController().release(drawable)
    }
  }

  enum class ReleaseStrategy {
    IMMEDIATE,
    DELAYED,
    NEXT_FRAME;

    companion object {
      @JvmStatic
      fun parse(value: Long): ReleaseStrategy =
          when (value) {
            2L -> IMMEDIATE
            1L -> DELAYED
            else -> NEXT_FRAME
          }
    }
  }

  enum class Prefetch {
    AUTO,
    YES,
    NO;

    companion object {
      @JvmStatic
      fun parsePrefetch(value: Long): Prefetch =
          when (value) {
            2L -> NO
            1L -> YES
            else -> AUTO
          }
    }
  }
}

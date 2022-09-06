package com.chartboost.helium.mintegraladapter

import android.app.Activity
import android.content.Context
import android.util.Size
import android.widget.FrameLayout
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterFailureEvents.*
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterSuccessEvents.*
import com.mbridge.msdk.MBridgeConstans
import com.mbridge.msdk.interstitialvideo.out.InterstitialVideoListener
import com.mbridge.msdk.interstitialvideo.out.MBBidInterstitialVideoHandler
import com.mbridge.msdk.interstitialvideo.out.MBInterstitialVideoHandler
import com.mbridge.msdk.mbbid.out.BidManager
import com.mbridge.msdk.out.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium Mintegral Adapter.
 */
class MintegralAdapter : PartnerAdapter {
    companion object {
        /**
         * Flag that can optionally be set to mute video creatives served by Mintegral. This can be
         * set at any time and will take effect for the next ad request.
         *
         * https://dev.mintegral.com/doc/index.html?file=sdk-m_sdk-android&lang=en
         */
        public var mute = false
            set(value) {
                field = value
                PartnerLogController.log(
                    CUSTOM,
                    "Mintegral video creatives will be ${if (value) "muted" else "unmuted"}."
                )
            }

        /**
         * Key for parsing the Mintegral app ID
         */
        private const val APP_ID_KEY = "mintegral_app_id"

        /**
         * Key for parsing the Mintegral app key
         */
        private const val APP_KEY_KEY = "app_key"

        /**
         * Key for parsing the Mintegral unity ID
         */
        private const val UNIT_ID_KEY = "mintegral_unit_id"
    }

    /**
     * Lambda to be called for a successful Mintegral ad show.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * Lambda to be called for a failed Mintegral ad show.
     */
    private var onShowFailure: () -> Unit = {}

    /**
     * Track whether the Mintegral SDK has been successfully initialized.
     */
    private var isSdkInitialized = false

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies = false

    /**
     * Get the Mintegral SDK version.
     */
    override val partnerSdkVersion: String
        get() = MBConfiguration.SDK_VERSION

    /**
     * Get the Mintegral adapter version.
     *
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_MINTEGRAL_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "mintegral"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Mintegral"

    /**
     * Initialize the Mintegral SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Mintegral.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        val appId = partnerConfiguration.credentials[APP_ID_KEY]
        val appKey = partnerConfiguration.credentials[APP_KEY_KEY]

        if (!canInitialize(appId, appKey)) {
            return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
        }

        return suspendCoroutine { continuation ->
            MBridgeSDKFactory.getMBridgeSDK().apply {
                init(
                    getMBConfigurationMap(appId, appKey),
                    context,
                    object : SDKInitStatusListener {
                        override fun onInitSuccess() {
                            isSdkInitialized = true
                            continuation.resume(
                                Result.success(
                                    PartnerLogController.log(
                                        SETUP_SUCCEEDED
                                    )
                                )
                            )
                        }

                        override fun onInitFail(error: String?) {
                            PartnerLogController.log(SETUP_FAILED, "$error")
                            continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
                        }
                    }
                )
            }
        }
    }

    /**
     * Save the current GDPR applicability state for later use.
     *
     * @param context The current [Context].
     * @param gdprApplies True if GDPR applies, false otherwise.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        this.gdprApplies = gdprApplies
    }

    /**
     * Notify Mintegral of the user's GDPR consent status, if applicable.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        if (gdprApplies && isSdkInitialized) {
            MBridgeSDKFactory.getMBridgeSDK()?.setConsentStatus(
                context,
                if (gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED)
                    MBridgeConstans.IS_SWITCH_ON
                else
                    MBridgeConstans.IS_SWITCH_OFF
            )
        }
    }

    /**
     * Notify Mintegral of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGivenCcpaConsent True if the user has given CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGivenCcpaConsent: Boolean,
        privacyString: String?
    ) {
        if (isSdkInitialized) MBridgeSDKFactory.getMBridgeSDK()
            ?.setDoNotTrackStatus(!hasGivenCcpaConsent)
    }

    /**
     * Notify Mintegral of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        if (isSdkInitialized) MBridgeSDKFactory.getMBridgeSDK()
            ?.setDoNotTrackStatus(isSubjectToCoppa)
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return mapOf<String, String>("buyeruid" to BidManager.getBuyerUid(context))
    }

    /**
     * Attempt to load a Mintegral ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        val unitId = request.partnerSettings[UNIT_ID_KEY] ?: ""

        if (!canLoadAd(context, request.partnerPlacement, unitId)) {
            return Result.failure(HeliumAdException(HeliumErrorCode.INVALID_BID_PAYLOAD))
        }

        return when (request.format) {
            AdFormat.BANNER -> loadBannerAd(context, request, unitId, partnerAdListener)
            AdFormat.INTERSTITIAL -> loadInterstitialAd(context, request, unitId, partnerAdListener)
            AdFormat.REWARDED -> loadRewardedAd(context, request, unitId, partnerAdListener)
        }
    }

    /**
     * Attempt to show the currently loaded Mintegral ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return partnerAd.ad?.let {
            return suspendCancellableCoroutine { continuation ->
                when (it) {
                    is MBInterstitialVideoHandler -> if (it.isReady) it.show()
                    is MBBidInterstitialVideoHandler -> if (it.isBidReady) it.showFromBid()
                    is MBRewardVideoHandler -> if (it.isReady) it.show()
                    is MBBidRewardVideoHandler -> if (it.isBidReady) it.showFromBid()
                    is MBBannerView -> continuation.resume(Result.success(partnerAd))
                }

                onShowSuccess = {
                    PartnerLogController.log(SHOW_SUCCEEDED)
                    continuation.resume(Result.success(partnerAd))
                }

                onShowFailure = {
                    PartnerLogController.log(SHOW_FAILED)
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL)))
                }
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Discard unnecessary Mintegral ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        // Only invalidating banner ads as there are no fullscreen invalidation APIs.
        return partnerAd.ad?.let {
            if (it is MBBannerView) {
                it.release()
            }

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }

    /**
     * Check if the Mintegral SDK can initialize using the provided config.
     *
     * @param appId The Mintegral app ID, if any.
     * @param appKey The Mintegral app key, if any.
     *
     * @return True if the Mintegral SDK can initialize, false otherwise.
     */
    private fun canInitialize(appId: String?, appKey: String?): Boolean {
        return when {
            appId.isNullOrEmpty() -> {
                PartnerLogController.log(SETUP_FAILED, "The app ID is null/empty.")
                false
            }
            appKey.isNullOrEmpty() -> {
                PartnerLogController.log(SETUP_FAILED, "The app key is null/empty.")
                false
            }
            else -> true
        }
    }

    /**
     * Check whether ads can be loaded.
     *
     * @param context The current [Context].
     * @param partnerPlacement The placement for the ad.
     * @param partnerUnitId The unit ID for the ad.
     *
     * @return True if ads can be loaded, false otherwise.
     */
    private fun canLoadAd(
        context: Context,
        partnerPlacement: String,
        partnerUnitId: String
    ): Boolean {
        return when {
            !isSdkInitialized -> {
                PartnerLogController.log(LOAD_FAILED, "The SDK is not initialized.")
                false
            }
            context !is Activity -> {
                PartnerLogController.log(LOAD_FAILED, "Context must be an Activity.")
                false
            }
            partnerPlacement.isEmpty() || partnerUnitId.isEmpty() -> {
                PartnerLogController.log(LOAD_FAILED, "Missing placement or unit ID.")
                false
            }
            else -> true
        }
    }

    /**
     * Attempt to load a Mintegral banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        val adm = request.adm
        val size = getMintegralBannerSize(request.size)

        return suspendCoroutine { continuation ->
            val ad = MBBannerView(context)
            ad.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            ad.init(size, request.partnerPlacement, partnerUnitId)
            ad.setBannerAdListener(object : BannerAdListener {
                override fun onLoadFailed(p0: MBridgeIds?, error: String?) {
                    PartnerLogController.log(
                        LOAD_FAILED,
                        "Placement: ${request.partnerPlacement}. Error: $error"
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onLoadSuccessed(p0: MBridgeIds?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(
                            PartnerAd(ad = ad, details = emptyMap(), request = request)
                        )
                    )
                }

                override fun onLogImpression(p0: MBridgeIds?) {
                    PartnerLogController.log(DID_TRACK_IMPRESSION)
                    listener.onPartnerAdImpression(
                        PartnerAd(ad = ad, details = emptyMap(), request = request)
                    )
                }

                override fun onClick(p0: MBridgeIds?) {
                    PartnerLogController.log(DID_CLICK)
                    listener.onPartnerAdClicked(
                        PartnerAd(ad = ad, details = emptyMap(), request = request)
                    )
                }

                override fun onLeaveApp(p0: MBridgeIds?) {
                }

                override fun showFullScreen(p0: MBridgeIds?) {
                }

                override fun closeFullScreen(p0: MBridgeIds?) {
                }

                override fun onCloseBanner(p0: MBridgeIds?) {
                }
            })

            if (!adm.isNullOrEmpty()) {
                ad.loadFromBid(adm)
            } else {
                ad.load()
            }
        }
    }

    /**
     * Convert a Helium banner size into the corresponding Mintegral banner size.
     *
     * @param size The Helium banner size.
     *
     * @return The Mintegral banner size.
     */
    private fun getMintegralBannerSize(size: Size?): BannerSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> BannerSize(BannerSize.STANDARD_TYPE, 320, 50)
                it in 90 until 250 -> BannerSize(BannerSize.DEV_SET_TYPE, 728, 90)
                it >= 250 -> BannerSize(BannerSize.MEDIUM_TYPE, 300, 250)
                else -> BannerSize(BannerSize.STANDARD_TYPE, 320, 50)
            }
        } ?: BannerSize(BannerSize.STANDARD_TYPE, 320, 50)
    }

    /**
     * Attempt to load a Mintegral interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        val adm = request.adm

        return if (!adm.isNullOrEmpty()) {
            loadBiddingInterstitialAd(context, request, adm, partnerUnitId, listener)
        } else {
            loadNonBiddingInterstitialAd(context, request, partnerUnitId, listener)
        }
    }

    /**
     * Attempt to load a bidding Mintegral interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param adm The ad markup.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBiddingInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        adm: String,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val ad = MBBidInterstitialVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setInterstitialVideoListener(object : InterstitialVideoListener {
                override fun onLoadSuccess(p0: MBridgeIds?) {
                }

                override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request))
                    )
                }

                override fun onVideoLoadFail(p0: MBridgeIds?, error: String?) {
                    PartnerLogController.log(
                        LOAD_FAILED,
                        "Placement: ${request.partnerPlacement}. Error: $error"
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onAdShow(p0: MBridgeIds?) {
                    onShowSuccess()
                }

                override fun onAdClose(p0: MBridgeIds?, p1: RewardInfo?) {
                    PartnerLogController.log(DID_DISMISS)
                    listener.onPartnerAdDismissed(
                        PartnerAd(ad = ad, details = emptyMap(), request = request), null
                    )
                }

                override fun onShowFail(p0: MBridgeIds?, p1: String?) {
                    onShowFailure()
                }

                override fun onVideoAdClicked(p0: MBridgeIds?) {
                    PartnerLogController.log(DID_CLICK)
                    listener.onPartnerAdClicked(
                        PartnerAd(ad = ad, details = emptyMap(), request = request)
                    )
                }

                override fun onVideoComplete(p0: MBridgeIds?) {
                }

                override fun onAdCloseWithIVReward(p0: MBridgeIds?, p1: RewardInfo?) {
                }

                override fun onEndcardShow(p0: MBridgeIds?) {
                }
            })

            ad.loadFromBid(adm)
        }
    }

    /**
     * Attempt to load a non-bidding Mintegral interstitial ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadNonBiddingInterstitialAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val ad = MBInterstitialVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setInterstitialVideoListener(object : InterstitialVideoListener {
                override fun onLoadSuccess(p0: MBridgeIds?) {
                }

                override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request))
                    )
                }

                override fun onVideoLoadFail(p0: MBridgeIds?, error: String?) {
                    PartnerLogController.log(
                        LOAD_FAILED,
                        "Placement: ${request.partnerPlacement}. Error: $error"
                    )
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onAdShow(p0: MBridgeIds?) {
                    onShowSuccess()
                }

                override fun onAdClose(p0: MBridgeIds?, p1: RewardInfo?) {
                    PartnerLogController.log(DID_DISMISS)
                    listener.onPartnerAdDismissed(
                        PartnerAd(ad = ad, details = emptyMap(), request = request), null
                    )
                }

                override fun onShowFail(p0: MBridgeIds?, p1: String?) {
                    onShowFailure()
                }

                override fun onVideoAdClicked(p0: MBridgeIds?) {
                    PartnerLogController.log(DID_CLICK)
                    listener.onPartnerAdClicked(
                        PartnerAd(ad = ad, details = emptyMap(), request = request)
                    )
                }

                override fun onVideoComplete(p0: MBridgeIds?) {
                }

                override fun onAdCloseWithIVReward(p0: MBridgeIds?, p1: RewardInfo?) {
                }

                override fun onEndcardShow(p0: MBridgeIds?) {
                }
            })

            ad.load()
        }
    }

    /**
     * Attempt to load a Mintegral rewarded ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        val adm = request.adm

        return if (!adm.isNullOrEmpty()) {
            loadBiddingRewardedAd(context, request, adm, partnerUnitId, listener)
        } else {
            loadNonBiddingRewardedAd(context, request, partnerUnitId, listener)
        }
    }

    /**
     * Attempt to load a bidding Mintegral rewarded ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param adm The ad markup.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBiddingRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        adm: String,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val ad = MBBidRewardVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setRewardVideoListener(object : RewardVideoListener {
                override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request))
                    )
                }

                override fun onLoadSuccess(p0: MBridgeIds?) {
                }

                override fun onVideoLoadFail(p0: MBridgeIds?, error: String?) {
                    PartnerLogController.log(LOAD_FAILED, "$error")
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onAdShow(p0: MBridgeIds?) {
                    onShowSuccess()
                }

                override fun onAdClose(p0: MBridgeIds?, rewardInfo: RewardInfo?) {
                    PartnerLogController.log(DID_REWARD)
                    listener.onPartnerAdRewarded(
                        PartnerAd(ad = ad, details = emptyMap(), request = request), Reward(
                            rewardInfo?.rewardAmount?.toInt() ?: 0,
                            rewardInfo?.rewardName ?: ""
                        )
                    )
                }

                override fun onShowFail(p0: MBridgeIds?, p1: String?) {
                    onShowFailure()
                }

                override fun onVideoAdClicked(p0: MBridgeIds?) {
                    PartnerLogController.log(DID_CLICK)
                    listener.onPartnerAdClicked(
                        PartnerAd(ad = ad, details = emptyMap(), request = request)
                    )
                }

                override fun onVideoComplete(p0: MBridgeIds?) {
                }

                override fun onEndcardShow(p0: MBridgeIds?) {
                }
            })

            ad.loadFromBid(adm)
        }
    }

    /**
     * Attempt to load a non-bidding Mintegral rewarded ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerUnitId The Mintegral unit ID for the ad.
     * @param listener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadNonBiddingRewardedAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerUnitId: String,
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            val ad = MBRewardVideoHandler(context, request.partnerPlacement, partnerUnitId)
            ad.playVideoMute(if (mute) MBridgeConstans.REWARD_VIDEO_PLAY_MUTE else MBridgeConstans.REWARD_VIDEO_PLAY_NOT_MUTE)
            ad.setRewardVideoListener(object : RewardVideoListener {
                override fun onVideoLoadSuccess(p0: MBridgeIds?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(PartnerAd(ad = ad, details = emptyMap(), request = request))
                    )
                }

                override fun onLoadSuccess(p0: MBridgeIds?) {
                }

                override fun onVideoLoadFail(p0: MBridgeIds?, error: String?) {
                    PartnerLogController.log(LOAD_FAILED, "$error")
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }

                override fun onAdShow(p0: MBridgeIds?) {
                    onShowSuccess()
                }

                override fun onAdClose(p0: MBridgeIds?, rewardInfo: RewardInfo?) {
                    PartnerLogController.log(DID_REWARD)
                    listener.onPartnerAdRewarded(
                        PartnerAd(ad = ad, details = emptyMap(), request = request), Reward(
                            rewardInfo?.rewardAmount?.toInt() ?: 0,
                            rewardInfo?.rewardName ?: ""
                        )
                    )
                }

                override fun onShowFail(p0: MBridgeIds?, p1: String?) {
                    onShowFailure()
                }

                override fun onVideoAdClicked(p0: MBridgeIds?) {
                    PartnerLogController.log(DID_CLICK)
                    listener.onPartnerAdClicked(
                        PartnerAd(ad = ad, details = emptyMap(), request = request)
                    )
                }

                override fun onVideoComplete(p0: MBridgeIds?) {
                }

                override fun onEndcardShow(p0: MBridgeIds?) {
                }
            })

            ad.load()
        }
    }
}

/*
 * Copyright 2024-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.mintegraladapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.mbridge.msdk.out.MBConfiguration

object MintegralAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "mintegral"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Mintegral"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion = MBConfiguration.SDK_VERSION

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_MINTEGRAL_ADAPTER_VERSION

    /**
     * Flag that can optionally be set to mute video creatives served by Mintegral. This can be
     * set at any time and will take effect for the next ad request.
     *
     * https://dev.mintegral.com/doc/index.html?file=sdk-m_sdk-android&lang=en
     */
    var mute = false
        set(value) {
            field = value
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "Mintegral video creatives will be ${if (value) "muted" else "unmuted"}.",
            )
        }
}

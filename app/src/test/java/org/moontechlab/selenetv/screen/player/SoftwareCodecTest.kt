package org.moontechlab.selenetv.screen.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareCodecTest {
    @Test fun googleAndC2_areSoftware() {
        assertTrue(isSoftwareCodec("OMX.google.h264.decoder"))
        assertTrue(isSoftwareCodec("c2.android.avc.decoder"))
        assertTrue(isSoftwareCodec("C2.Android.HEVC.Decoder")) // 大小写不敏感
    }
    @Test fun vendorHardware_isNotSoftware() {
        assertFalse(isSoftwareCodec("OMX.qcom.video.decoder.avc"))
        assertFalse(isSoftwareCodec("c2.mtk.avc.decoder"))
        assertFalse(isSoftwareCodec("OMX.MTK.VIDEO.DECODER.HEVC"))
    }
}

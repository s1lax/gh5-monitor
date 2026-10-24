package com.lumixmonitor.lumix

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSettingProtocolTest {
    @Test fun movieConfigurationUsesSeparateOperationFromPhotoConfiguration(){
        assertEquals(0x9403,RemoteSetting.writeOpcode(0x02000012))
        assertEquals(0x9403,RemoteSetting.writeOpcode(0x02000052))
        for(code in listOf(0x06000011,0x06000012,0x06000013,0x06000014)){
            assertEquals(0x9409,RemoteSetting.writeOpcode(code))
        }
    }
}

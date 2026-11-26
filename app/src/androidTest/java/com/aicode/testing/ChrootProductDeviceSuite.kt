package com.aicode.testing

import com.aicode.feature.agent.domain.container.chroot.ChrootBrokerFdDeviceTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(ChrootBrokerFdDeviceTest::class, ChrootProductRuntimeDeviceTest::class)
class ChrootProductDeviceSuite

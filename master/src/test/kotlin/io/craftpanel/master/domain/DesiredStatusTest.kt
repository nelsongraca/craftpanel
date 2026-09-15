package io.craftpanel.master.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DesiredStatusTest :
    FunSpec({

        test("DesiredStatus round-trips through its DB string") {
            DesiredStatus.fromDb(DesiredStatus.RUNNING.toDb()) shouldBe DesiredStatus.RUNNING
            DesiredStatus.fromDb(DesiredStatus.STOPPED.toDb()) shouldBe DesiredStatus.STOPPED
            DesiredStatus.fromDb(null) shouldBe null
        }

        test("synthesizeStatus - desired RUNNING") {
            synthesizeStatus(DesiredStatus.RUNNING, ServerStatus.HEALTHY) shouldBe ServerStatus.HEALTHY
            synthesizeStatus(DesiredStatus.RUNNING, ServerStatus.STARTING) shouldBe ServerStatus.STARTING
            // Intent up, container down (yet) — presented as (re)starting, never as stopped.
            synthesizeStatus(DesiredStatus.RUNNING, ServerStatus.STOPPED) shouldBe ServerStatus.STARTING
            synthesizeStatus(DesiredStatus.RUNNING, ServerStatus.UNHEALTHY) shouldBe ServerStatus.UNHEALTHY
            synthesizeStatus(DesiredStatus.RUNNING, ServerStatus.CRASH_LOOPED) shouldBe ServerStatus.CRASH_LOOPED
        }

        test("synthesizeStatus - desired STOPPED") {
            synthesizeStatus(DesiredStatus.STOPPED, ServerStatus.STOPPED) shouldBe ServerStatus.STOPPED
            synthesizeStatus(DesiredStatus.STOPPED, ServerStatus.STARTING) shouldBe ServerStatus.STOPPING
            synthesizeStatus(DesiredStatus.STOPPED, ServerStatus.HEALTHY) shouldBe ServerStatus.STOPPING
            synthesizeStatus(DesiredStatus.STOPPED, ServerStatus.UNHEALTHY) shouldBe ServerStatus.STOPPING
            // A crash-looped server under STOPPED intent is effectively stopped.
            synthesizeStatus(DesiredStatus.STOPPED, ServerStatus.CRASH_LOOPED) shouldBe ServerStatus.STOPPED
        }

        test("synthesizeStatus - unset desired surfaces the reported status verbatim") {
            synthesizeStatus(null, ServerStatus.HEALTHY) shouldBe ServerStatus.HEALTHY
            synthesizeStatus(null, ServerStatus.STOPPED) shouldBe ServerStatus.STOPPED
            synthesizeStatus(null, ServerStatus.UNHEALTHY) shouldBe ServerStatus.UNHEALTHY
            synthesizeStatus(null, ServerStatus.CRASH_LOOPED) shouldBe ServerStatus.CRASH_LOOPED
        }
    })
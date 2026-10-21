package com.aeonlab.toolkit.hooks.items.script

import com.aeonlab.toolkit.media.AudioTransformBridge

class ScriptAudioBridge internal constructor(
    bridge: ScriptPluginBridge
) : AudioTransformBridge({ message -> bridge.log(message) })

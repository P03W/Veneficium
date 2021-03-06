/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package net.vene.magic.handling

import net.vene.ConfigInstance
import net.vene.VeneMain
import net.vene.common.util.extension.devDebug
import net.vene.magic.SpellContext
import net.vene.magic.spell_components.MagicEffect
import net.vene.magic.spell_components.types.BuiltinComponentType
import net.vene.magic.spell_components.types.MaterialComponent
import kotlin.random.Random

class SpellQueue {
    val componentList: MutableList<MagicEffect> = mutableListOf()
    private val modifiers: MutableList<MaterialComponent> = mutableListOf()
    private var frozenContinue = 0
    var tmpIndex = 0

    fun run(context: SpellContext) {
        if (ConfigInstance.spellQueueTraceback) {
            devDebug("------ Queue Run ------")
        }

        tmpIndex = frozenContinue
        frozenContinue = 0
        while (tmpIndex < componentList.size) {
            val magicEffect = componentList[tmpIndex]
            if (Random.nextDouble() < magicEffect.instability) {
                devDebug("Not executing component $magicEffect due to instability")
                tmpIndex++
                continue
            }

            if (ConfigInstance.spellQueueTraceback) {
                devDebug("Executing component $magicEffect")
            }

            val operation: HandlerOperation
            try {
                operation = magicEffect.exec(context, modifiers, this)
            } catch (thrown: Throwable) {
                VeneMain.LOGGER.error("EXECUTE COMPONENT FAILED!")
                VeneMain.LOGGER.error("Attempting to execute $magicEffect yielded a throwable")
                VeneMain.LOGGER.error("State After Error: $this")
                VeneMain.LOGGER.error("Index: $tmpIndex")
                VeneMain.LOGGER.error("Throwable: ${thrown.message}")
                VeneMain.LOGGER.error("Ditching component in attempt to recover from error")
                componentList.remove(magicEffect)
                continue
            }
            if (ConfigInstance.spellQueueTraceback) {
                devDebug("Returned Operation is $operation")
            }

            val result = handleOp(operation, magicEffect)

            if (result.stop) {
                break
            }
            if (result.increment) {
                tmpIndex++
            }
        }
    }

    /**
     * Not private to allow "safely" messing with queue from other components
     *
     * Only MATERIAL_MOVE and the REMOVE operations actually do anything by themselves
     * And FREEZE technically works but is somewhat sketchy
     */
    fun handleOp(operation: HandlerOperation, magicEffect: MagicEffect): OpResult {
        when (operation) {
            HandlerOperation.REMOVE_CONTINUE -> {
                componentList.remove(magicEffect)
                return OpResult(increment = false, stop = false)
            }
            HandlerOperation.REMOVE_STOP -> {
                componentList.remove(magicEffect)
                return OpResult(increment = false, stop = true)
            }
            HandlerOperation.STAY_CONTINUE -> {
                return OpResult(increment = true, stop = false)
            }
            HandlerOperation.STAY_STOP -> {
                return OpResult(increment = false, stop = true)
            }
            HandlerOperation.MATERIAL_MOVE -> {
                return if (magicEffect.type == BuiltinComponentType.MATERIAL) {
                    modifiers.add(magicEffect as MaterialComponent)
                    componentList.remove(magicEffect)
                    OpResult(increment = false, stop = false)
                } else {
                    // Functionally equivalent to REMOVE_CONTINUE
                    componentList.remove(magicEffect)
                    OpResult(increment = false, stop = false)
                }
            }
            HandlerOperation.FREEZE -> {
                frozenContinue = tmpIndex
                return OpResult(increment = false, stop = true)
            }
        }
    }

    fun isEmpty(): Boolean {
        return componentList.isEmpty()
    }

    fun addToQueue(effect: MagicEffect) {
        componentList.add(effect)
    }

    fun copy(): SpellQueue {
        val copy = SpellQueue()
        copy.componentList.addAll(componentList)
        copy.modifiers.addAll(modifiers)
        copy.frozenContinue = frozenContinue
        return copy
    }

    override fun toString(): String {
        return "SpellQueue[Components: $componentList, Materials: $modifiers]"
    }

    data class OpResult(val increment: Boolean, val stop: Boolean)
}

enum class HandlerOperation {
    REMOVE_CONTINUE,
    REMOVE_STOP,
    STAY_CONTINUE,
    STAY_STOP,
    MATERIAL_MOVE,
    FREEZE,
}

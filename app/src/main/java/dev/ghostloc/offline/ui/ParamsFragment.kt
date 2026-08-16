package dev.ghostloc.offline.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.ghostloc.offline.R
import dev.ghostloc.offline.databinding.FragmentParamsBinding

/** Params tab: coordinates, policy toggles, cell and jitter tuning. */
class ParamsFragment : Fragment(), ConfigDraft.Listener {

    private var binding: FragmentParamsBinding? = null
    private var syncing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentParamsBinding.inflate(inflater, container, false)
        this.binding = binding

        bindText(binding.latInput) { ConfigDraft.latText = it }
        bindText(binding.lonInput) { ConfigDraft.lonText = it }
        bindText(binding.speedInput) { ConfigDraft.speedText = it }
        bindText(binding.altitudeInput) { ConfigDraft.altitudeText = it }
        bindText(binding.accuracyInput) { ConfigDraft.accuracyText = it }
        bindText(binding.bearingInput) { ConfigDraft.bearingText = it }
        bindText(binding.mccInput) { ConfigDraft.mccText = it }
        bindText(binding.mncInput) { ConfigDraft.mncText = it }
        bindText(binding.countryInput) { ConfigDraft.countryText = it }
        bindText(binding.operatorInput) { ConfigDraft.operatorText = it }
        bindText(binding.gnssSatInput) { ConfigDraft.gnssSatText = it }
        bindText(binding.speedVariationInput) { ConfigDraft.speedVariationText = it }
        bindText(binding.accuracyJitterInput) { ConfigDraft.accuracyJitterText = it }
        bindText(binding.altitudeJitterInput) { ConfigDraft.altitudeJitterText = it }

        bindCheck(binding.wifiShieldCheck) { ConfigDraft.wifiShield = it }
        bindCheck(binding.cellShieldCheck) { ConfigDraft.cellShield = it }
        bindCheck(binding.mockShieldCheck) { ConfigDraft.mockShield = it }
        bindCheck(binding.movementCheck) { ConfigDraft.movement = it }
        bindCheck(binding.nrCheck) { ConfigDraft.nrEnabled = it }
        bindCheck(binding.nmeaCheck) { ConfigDraft.nmeaEnabled = it }
        bindCheck(binding.agpsCheck) { ConfigDraft.agpsEnabled = it }
        bindCheck(binding.vendorHooksCheck) { ConfigDraft.vendorHooksEnabled = it }

        syncFromDraft()
        ConfigDraft.addListener(this)
        return binding.root
    }

    override fun onDestroyView() {
        ConfigDraft.removeListener(this)
        binding = null
        super.onDestroyView()
    }

    override fun onDraftChanged(source: Any?) {
        syncFromDraft()
    }

    fun syncFromDraft() {
        val binding = binding ?: return
        syncing = true
        setTextIfDifferent(binding.latInput, ConfigDraft.latText)
        setTextIfDifferent(binding.lonInput, ConfigDraft.lonText)
        setTextIfDifferent(binding.speedInput, ConfigDraft.speedText)
        setTextIfDifferent(binding.altitudeInput, ConfigDraft.altitudeText)
        setTextIfDifferent(binding.accuracyInput, ConfigDraft.accuracyText)
        setTextIfDifferent(binding.bearingInput, ConfigDraft.bearingText)
        setTextIfDifferent(binding.mccInput, ConfigDraft.mccText)
        setTextIfDifferent(binding.mncInput, ConfigDraft.mncText)
        setTextIfDifferent(binding.countryInput, ConfigDraft.countryText)
        setTextIfDifferent(binding.operatorInput, ConfigDraft.operatorText)
        setTextIfDifferent(binding.gnssSatInput, ConfigDraft.gnssSatText)
        setTextIfDifferent(binding.speedVariationInput, ConfigDraft.speedVariationText)
        setTextIfDifferent(binding.accuracyJitterInput, ConfigDraft.accuracyJitterText)
        setTextIfDifferent(binding.altitudeJitterInput, ConfigDraft.altitudeJitterText)
        binding.wifiShieldCheck.isChecked = ConfigDraft.wifiShield
        binding.cellShieldCheck.isChecked = ConfigDraft.cellShield
        binding.mockShieldCheck.isChecked = ConfigDraft.mockShield
        binding.movementCheck.isChecked = ConfigDraft.movement
        binding.nrCheck.isChecked = ConfigDraft.nrEnabled
        binding.nmeaCheck.isChecked = ConfigDraft.nmeaEnabled
        binding.agpsCheck.isChecked = ConfigDraft.agpsEnabled
        binding.vendorHooksCheck.isChecked = ConfigDraft.vendorHooksEnabled
        syncing = false
    }

    private fun bindText(input: EditText, assign: (String) -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (syncing) {
                    return
                }
                assign(s?.toString()?.trim().orEmpty())
                ConfigDraft.notifyChanged(this@ParamsFragment)
            }
        })
    }

    private fun bindCheck(check: CompoundButton, assign: (Boolean) -> Unit) {
        check.setOnCheckedChangeListener { _, isChecked ->
            if (syncing) {
                return@setOnCheckedChangeListener
            }
            assign(isChecked)
            ConfigDraft.notifyChanged(this@ParamsFragment)
        }
    }

    private fun setTextIfDifferent(input: EditText, value: String) {
        if (input.text.toString() != value) {
            input.setText(value)
        }
    }
}

package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.midi.MidiManager;

/** Audio options shared by containers and shortcut overrides. */
public class AudioConfigDialog extends ContentDialog {
    public interface Config {
        String getAudioDriver();
        String getMidiSoundFont();
        int getVolumePercent();
        void apply(String audioDriver, String midiSoundFont, int volumePercent);
    }

    public AudioConfigDialog(Context context, Config config) {
        super(context, R.layout.audio_config_dialog);
        setIcon(R.drawable.icon_settings);
        setTitle(R.string.audio_configuration);

        Spinner audioDriver = findViewById(R.id.SAudioConfigDriver);
        Spinner midiSoundFont = findViewById(R.id.SAudioConfigSoundFont);
        SeekBar volume = findViewById(R.id.SBAudioVolume);
        TextView volumeValue = findViewById(R.id.TVAudiovolumeValue);

        AppUtils.setSpinnerSelectionFromIdentifier(audioDriver, config.getAudioDriver());
        MidiManager.loadSFSpinner(midiSoundFont);
        AppUtils.setSpinnerSelectionFromValue(midiSoundFont, config.getMidiSoundFont());

        int initialVolume = Math.max(0, Math.min(100, config.getVolumePercent()));
        volume.setProgress(initialVolume);
        volumeValue.setText(initialVolume + "%");
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(progress + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        setOnConfirmCallback(() -> {
            String driver = StringUtils.parseIdentifier(audioDriver.getSelectedItem());
            String soundFont = midiSoundFont.getSelectedItemPosition() == 0
                    ? "" : midiSoundFont.getSelectedItem().toString();
            config.apply(driver, soundFont, volume.getProgress());
        });
    }
}

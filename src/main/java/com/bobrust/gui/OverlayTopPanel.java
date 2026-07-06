package com.bobrust.gui;

import java.awt.*;

import javax.swing.*;

import com.bobrust.lang.RustTranslator;

public class OverlayTopPanel extends JPanel {
	final JLabel generationLabel;
	final JLabel generationInfo;
	
	public OverlayTopPanel() {
		setLayout(new BorderLayout());
		setBackground(new Color(0x333e48));
		setDoubleBuffered(true);
		
		generationLabel = new JLabel("No active generation");
		generationLabel.setHorizontalAlignment(SwingConstants.CENTER);
		generationLabel.setHorizontalTextPosition(SwingConstants.CENTER);
		generationLabel.setOpaque(false);
		generationLabel.setForeground(Color.lightGray);
		generationLabel.setFont(generationLabel.getFont().deriveFont(18.0f));
		add(generationLabel, BorderLayout.NORTH);
		
		generationInfo = new JLabel("");
		generationInfo.setHorizontalAlignment(SwingConstants.CENTER);
		generationInfo.setHorizontalTextPosition(SwingConstants.CENTER);
		generationInfo.setOpaque(false);
		generationInfo.setForeground(Color.WHITE);
		generationInfo.setFont(generationInfo.getFont().deriveFont(Font.BOLD, 16.0f));
		add(generationInfo, BorderLayout.CENTER);
	}
	
	public void setExactGenerationLabel(long time) {
		generationInfo.setText("Time %s".formatted(RustTranslator.getTimeMinutesMessage(time)));
	}

	/**
	 * Only updates the shape counter — the time readout is owned by the S2
	 * model-based estimate ({@link #setExactGenerationLabel}), which replaced
	 * the old {@code 1.3 × (14 + 1000/cps)} fudge that used to live here.
	 */
	public void setGeneratedShapes(int shapesUsed, int maxShapes) {
		generationLabel.setText("%d/%d shapes used".formatted(shapesUsed, maxShapes));
	}
	
	public void setDrawnShapes(int index, int maxShapes, long timeLeft) {
		generationLabel.setText("%d/%d shapes drawn".formatted(index, maxShapes));
		generationInfo.setText("Time left %s".formatted(RustTranslator.getTimeMinutesMessage(timeLeft)));
	}
}

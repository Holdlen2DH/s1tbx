/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.dat.dialogs;

import org.esa.s1tbx.dat.reports.Report;
import org.esa.snap.framework.ui.ModalDialog;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.util.DialogUtils;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 *
 */
public class ReportDialog extends ModalDialog {

    public ReportDialog(final Report report) {
        super(SnapApp.getDefault().getMainFrame(), SnapApp.getDefault().getAppName() + " Report Preview",
                ModalDialog.ID_CANCEL, null);    /*I18N*/

        getJDialog().setPreferredSize(new Dimension(800, 800));

        final JLabel reportLabel = new JLabel(report.getAsHTML());

        final JPanel dialogContent = new JPanel(new BorderLayout(4, 4));

        final JPanel labelPane = new JPanel(new BorderLayout());
        labelPane.add(BorderLayout.NORTH, reportLabel);
        dialogContent.add(BorderLayout.CENTER, new JScrollPane(labelPane));

        final JPanel buttonPanel = createButtonPanel();
        dialogContent.add(buttonPanel, BorderLayout.EAST);

        setContent(dialogContent);
    }

    @Override
    protected void onOther() {
        // override default behaviour by doing nothing
    }

    public static JPanel createButtonPanel() {
        final JPanel panel = new JPanel(new GridLayout(10, 1));

        final JButton pdfButton = DialogUtils.createButton("Export PDF", "Export PDF", null, panel, DialogUtils.ButtonStyle.Text);
        panel.add(pdfButton);
        pdfButton.addActionListener(new ActionListener() {
            public void actionPerformed(final ActionEvent e) {

            }
        });
        final JButton htmlButton = DialogUtils.createButton("Export HTML", "Export HTML", null, panel, DialogUtils.ButtonStyle.Text);
        panel.add(htmlButton);
        htmlButton.addActionListener(new ActionListener() {
            public void actionPerformed(final ActionEvent e) {


            }
        });

        return panel;
    }
}

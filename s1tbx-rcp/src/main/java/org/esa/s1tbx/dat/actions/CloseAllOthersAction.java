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
package org.esa.s1tbx.dat.actions;

import org.esa.snap.framework.datamodel.Product;
import org.esa.snap.framework.ui.command.CommandEvent;
import org.esa.snap.framework.ui.command.ExecCommand;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.util.MemUtils;
import org.esa.snap.visat.VisatApp;

/**
 * This action closes all opened products other than the one selected.
 */
public class CloseAllOthersAction extends ExecCommand {

    @Override
    public void actionPerformed(final CommandEvent event) {
        final Product selectedProduct = SnapApp.getDefault().getSelectedProduct();
        final Product[] products = SnapApp.getDefault().getProductManager().getProducts();
        for (int i = products.length - 1; i >= 0; i--) {
            if (products[i] != selectedProduct)
                VisatApp.getApp().closeProduct(products[i]);
        }
        // free cache
        MemUtils.freeAllMemory();
    }

    @Override
    public void updateState(final CommandEvent event) {
        setEnabled(SnapApp.getDefault().getProductManager().getProductCount() > 1 &&
                           SnapApp.getDefault().getSelectedProduct() != null);
    }
}

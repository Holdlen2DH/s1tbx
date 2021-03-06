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
package org.esa.s1tbx.sar.gpf.orbits;

import org.esa.snap.dataio.envisat.EnvisatOrbitReader;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Created by IntelliJ IDEA.
 * User: lveci
 * Date: Sep 4, 2008
 * To change this template use File | Settings | File Templates.
 */
public class TestApplyOrbitFile {

    File vorPath = new File("P:\\nest\\nest\\ESA Data\\Orbits\\Doris\\vor");

    @Test
    public void testOpenFile() {

        EnvisatOrbitReader reader = new EnvisatOrbitReader();
        assertNotNull(reader);
    }

    @Test
    @Ignore("not implemented")
    public void testProcessing() {
        assertTrue(false);
    }
}

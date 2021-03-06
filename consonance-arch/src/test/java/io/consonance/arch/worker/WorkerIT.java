/*
 *     Consonance - workflow software for multiple clouds
 *     Copyright (C) 2016 OICR
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package io.consonance.arch.worker;

import io.consonance.arch.coordinator.Coordinator;
import io.consonance.arch.jobGenerator.JobGenerator;
import io.consonance.arch.utils.CommonServerTestUtilities;
import org.apache.commons.io.FileUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 *
 * @author dyuen
 */
public class WorkerIT {

    @BeforeClass
    public static void setup() throws IOException, TimeoutException {
            CommonServerTestUtilities.clearState();
    }

    /**
     * Test of main method, of class JobGenerator.
     *
     * @throws java.lang.Exception
     */
    @Test
    public void testTestModeOperation() throws Exception {
        File file = FileUtils.getFile("src", "test", "resources", "config");
        File iniDir = FileUtils.getFile("ini");
        // prime the coordinator with an order
        JobGenerator
                .main(new String[] { "--config", file.getAbsolutePath(), "--ini", iniDir.getAbsolutePath(), "--flavour", "m1.xlarge" });
        // prime the worker with a job
        Coordinator.main(new String[] { "--config", file.getAbsolutePath() });
        // note that the specified flavour below needs to match the flavour above
        Worker.main(new String[] { "--config", file.getAbsolutePath(), "--uuid", "12345", "--test", "--pidFile",
                "/var/run/arch3_worker.pid", "--flavour","m1.xlarge" });
    }

}

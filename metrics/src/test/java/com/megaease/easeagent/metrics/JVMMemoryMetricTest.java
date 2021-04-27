/*
 * Copyright (c) 2017, MegaEase
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.megaease.easeagent.metrics;

import com.codahale.metrics.MetricRegistry;
import com.megaease.easeagent.metrics.jvm.memory.JVMMemoryMetric;
import org.junit.Test;

public class JVMMemoryMetricTest {

    @Test
    public void success() {
        MetricRegistry metricRegistry = new MetricRegistry();
        JVMMemoryMetric jvmMemoryMetric = new JVMMemoryMetric(metricRegistry, false);

        jvmMemoryMetric.doJob();

        // no exception is success
    }

}
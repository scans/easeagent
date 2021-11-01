/*
 * Copyright (c) 2021, MegaEase
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

package com.megaease.easeagent.core.matcher;

import com.megaease.easeagent.core.plugin.matcher.MethodMatcherConvert;
import com.megaease.easeagent.plugin.matcher.IMethodMatcher;
import com.megaease.easeagent.plugin.matcher.MethodMatcher;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

public class MethodMatcherTest {

    @SuppressWarnings("unused")
    public static class Foo {
        public void basicPublish(int a1, int a2, int a3, int a4) {
            System.out.println("sum:" + a1 + a2 + a3 + a4);
        }

        void basicConsume(int a1, int a2, int a3, int a4) {
            System.out.println("sum:" + a1 + a2 + a3 + a4);
        }
    }

    @Test
    public void testMatcher() {
        IMethodMatcher matcher = MethodMatcher.builder().named("basicPublish")
            .isPublic()
            .argsLength(4)
            .returnType("void")
            .qualifier("basicPublish")
            .build();

        ElementMatcher<MethodDescription> eMatcher = MethodMatcherConvert.INSTANCE.convert(matcher);
        Method reflectMethod;
        try {
            reflectMethod = Foo.class.getDeclaredMethod("basicPublish",
                int.class, int.class, int.class, int.class);
        } catch (Exception e) {
            reflectMethod = null;
        }
        Assert.assertNotNull(reflectMethod);
        MethodDescription method = new MethodDescription.ForLoadedMethod(reflectMethod);

        Assert.assertTrue(eMatcher.matches(method));

        matcher = MethodMatcher.builder().named("basicConsume")
            .isPublic()
            .argsLength(4)
            .qualifier("basicConsume")
            .build();

        eMatcher = MethodMatcherConvert.INSTANCE.convert(matcher);

        Assert.assertFalse(eMatcher.matches(method));
    }
}
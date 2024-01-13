/*
 * Copyright (c) 2024 New Vector Ltd
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

package ui

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
class ParameterizedRobolectricTestRunnerTest(private var uri: Uri) {
  @Test
  @Config(manifest = Config.NONE)
  fun parse() {
    val currentUri = Uri.parse("http://host/")
    assert(currentUri == uri)
  }

  companion object {
    @Parameters
    @JvmStatic
    fun getTestData(): Collection<*> {
      val data = arrayOf<Any>(Uri.parse("http://host/"))
      return listOf(data)
    }
  }
}

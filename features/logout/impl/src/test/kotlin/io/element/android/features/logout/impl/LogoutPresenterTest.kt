/*
 * Copyright (c) 2023 New Vector Ltd
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

package io.element.android.features.logout.impl

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.architecture.Async
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.encryption.BackupUploadState
import io.element.android.libraries.matrix.api.encryption.EncryptionService
import io.element.android.libraries.matrix.test.A_THROWABLE
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.encryption.FakeEncryptionService
import io.element.android.tests.testutils.WarmUpRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class LogoutPresenterTest {

    @get:Rule
    val warmUpRule = WarmUpRule()

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createLogoutPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.isLastSession).isFalse()
            assertThat(initialState.backupUploadState).isEqualTo(BackupUploadState.Unknown)
            assertThat(initialState.showConfirmationDialog).isFalse()
            assertThat(initialState.logoutAction).isEqualTo(Async.Uninitialized)
        }
    }

    @Test
    fun `present - initial state - last session`() = runTest {
        val presenter = createLogoutPresenter(
            encryptionService = FakeEncryptionService().apply {
                givenIsLastDevice(true)
            }
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            skipItems(1)
            val initialState = awaitItem()
            assertThat(initialState.isLastSession).isTrue()
            assertThat(initialState.backupUploadState).isEqualTo(BackupUploadState.Unknown)
            assertThat(initialState.showConfirmationDialog).isFalse()
            assertThat(initialState.logoutAction).isEqualTo(Async.Uninitialized)
        }
    }

    @Test
    fun `present - initial state - backing up`() = runTest {
        val encryptionService = FakeEncryptionService()
        val presenter = createLogoutPresenter(
            encryptionService = encryptionService
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            assertThat(initialState.isLastSession).isFalse()
            assertThat(initialState.backupUploadState).isEqualTo(BackupUploadState.Unknown)
            assertThat(initialState.showConfirmationDialog).isFalse()
            assertThat(initialState.logoutAction).isEqualTo(Async.Uninitialized)
            encryptionService.emitBackupUploadState(BackupUploadState.Uploading(backedUpCount = 1, totalCount = 2))
            val state = awaitItem()
            assertThat(state.backupUploadState).isEqualTo(BackupUploadState.Uploading(backedUpCount = 1, totalCount = 2))
            encryptionService.emitBackupUploadState(BackupUploadState.Done)
            val doneState = awaitItem()
            assertThat(doneState.backupUploadState).isEqualTo(BackupUploadState.Done)
        }
    }

    @Test
    fun `present - logout then cancel`() = runTest {
        val presenter = createLogoutPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            val confirmationState = awaitItem()
            assertThat(confirmationState.showConfirmationDialog).isTrue()
            initialState.eventSink.invoke(LogoutEvents.CloseDialogs)
            val finalState = awaitItem()
            assertThat(finalState.showConfirmationDialog).isFalse()
        }
    }

    @Test
    fun `present - logout then confirm`() = runTest {
        val presenter = createLogoutPresenter()
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            val confirmationState = awaitItem()
            assertThat(confirmationState.showConfirmationDialog).isTrue()
            confirmationState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            skipItems(1)
            val loadingState = awaitItem()
            assertThat(loadingState.showConfirmationDialog).isFalse()
            assertThat(loadingState.logoutAction).isInstanceOf(Async.Loading::class.java)
            val successState = awaitItem()
            assertThat(successState.logoutAction).isInstanceOf(Async.Success::class.java)
        }
    }

    @Test
    fun `present - logout with error then cancel`() = runTest {
        val matrixClient = FakeMatrixClient().apply {
            givenLogoutError(A_THROWABLE)
        }
        val presenter = createLogoutPresenter(
            matrixClient,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            val confirmationState = awaitItem()
            assertThat(confirmationState.showConfirmationDialog).isTrue()
            confirmationState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            skipItems(1)
            val loadingState = awaitItem()
            assertThat(loadingState.showConfirmationDialog).isFalse()
            assertThat(loadingState.logoutAction).isInstanceOf(Async.Loading::class.java)
            val errorState = awaitItem()
            assertThat(errorState.logoutAction).isEqualTo(Async.Failure<LogoutState>(A_THROWABLE))
            errorState.eventSink.invoke(LogoutEvents.CloseDialogs)
            val finalState = awaitItem()
            assertThat(finalState.logoutAction).isEqualTo(Async.Uninitialized)
        }
    }

    @Test
    fun `present - logout with error then force`() = runTest {
        val matrixClient = FakeMatrixClient().apply {
            givenLogoutError(A_THROWABLE)
        }
        val presenter = createLogoutPresenter(
            matrixClient,
        )
        moleculeFlow(RecompositionMode.Immediate) {
            presenter.present()
        }.test {
            val initialState = awaitItem()
            initialState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            val confirmationState = awaitItem()
            assertThat(confirmationState.showConfirmationDialog).isTrue()
            confirmationState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = false))
            skipItems(1)
            val loadingState = awaitItem()
            assertThat(loadingState.showConfirmationDialog).isFalse()
            assertThat(loadingState.logoutAction).isInstanceOf(Async.Loading::class.java)
            val errorState = awaitItem()
            assertThat(errorState.logoutAction).isEqualTo(Async.Failure<LogoutState>(A_THROWABLE))
            errorState.eventSink.invoke(LogoutEvents.Logout(ignoreSdkError = true))
            val loadingState2 = awaitItem()
            assertThat(loadingState2.showConfirmationDialog).isFalse()
            assertThat(loadingState2.logoutAction).isInstanceOf(Async.Loading::class.java)
            val successState = awaitItem()
            assertThat(successState.logoutAction).isInstanceOf(Async.Success::class.java)
        }
    }

    private fun createLogoutPresenter(
        matrixClient: MatrixClient = FakeMatrixClient(),
        encryptionService: EncryptionService = FakeEncryptionService(),
    ): LogoutPresenter = LogoutPresenter(
        matrixClient = matrixClient,
        encryptionService = encryptionService,
    )
}


/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.credentials;

import static android.content.Context.CREDENTIAL_SERVICE;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCredentialOption;
import android.credentials.GetCredentialRequest;
import android.credentials.IClearCredentialSessionCallback;
import android.credentials.ICreateCredentialCallback;
import android.credentials.ICredentialManager;
import android.credentials.IGetCredentialCallback;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.credentials.GetCredentialsRequest;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.SecureSettingsServiceNameResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Entry point service for credential management.
 *
 * <p>This service provides the {@link ICredentialManager} implementation and keeps a list of
 * {@link CredentialManagerServiceImpl} per user; the real work is done by
 * {@link CredentialManagerServiceImpl} itself.
 */
public final class CredentialManagerService extends
        AbstractMasterSystemService<CredentialManagerService, CredentialManagerServiceImpl> {

    private static final String TAG = "CredManSysService";

    public CredentialManagerService(@NonNull Context context) {
        super(context,
                new SecureSettingsServiceNameResolver(context, Settings.Secure.CREDENTIAL_SERVICE,
                        /*isMultipleMode=*/true),
                null, PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    protected String getServiceSettingsProperty() {
        return Settings.Secure.CREDENTIAL_SERVICE;
    }

    @Override // from AbstractMasterSystemService
    protected CredentialManagerServiceImpl newServiceLocked(@UserIdInt int resolvedUserId,
            boolean disabled) {
        // This method should not be called for CredentialManagerService as it is configured to use
        // multiple services.
        Slog.w(TAG, "Should not be here - CredentialManagerService is configured to use "
                + "multiple services");
        return null;
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(CREDENTIAL_SERVICE, new CredentialManagerServiceStub());
    }

    @Override // from AbstractMasterSystemService
    protected List<CredentialManagerServiceImpl> newServiceListLocked(int resolvedUserId,
            boolean disabled, String[] serviceNames) {
        if (serviceNames == null || serviceNames.length == 0) {
            Slog.i(TAG, "serviceNames sent in newServiceListLocked is null, or empty");
            return new ArrayList<>();
        }
        List<CredentialManagerServiceImpl> serviceList = new ArrayList<>(serviceNames.length);
        for (String serviceName : serviceNames) {
            Log.i(TAG, "in newServiceListLocked, service: " + serviceName);
            if (TextUtils.isEmpty(serviceName)) {
                continue;
            }
            try {
                serviceList.add(new CredentialManagerServiceImpl(this, mLock, resolvedUserId,
                        serviceName));
            } catch (PackageManager.NameNotFoundException | SecurityException e) {
                Log.i(TAG, "Unable to add serviceInfo : " + e.getMessage());
            }
        }
        return serviceList;
    }

    private void runForUser(@NonNull final Consumer<CredentialManagerServiceImpl> c) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                final List<CredentialManagerServiceImpl> services =
                        getServiceListForUserLocked(userId);
                for (CredentialManagerServiceImpl s : services) {
                    c.accept(s);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private List<ProviderSession> initiateProviderSessions(RequestSession session,
            List<String> requestOptions) {
        List<ProviderSession> providerSessions = new ArrayList<>();
        // Invoke all services of a user to initiate a provider session
        runForUser((service) -> {
            if (service.isServiceCapable(requestOptions)) {
                ProviderSession providerSession = service
                        .initiateProviderSessionForRequest(session);
                if (providerSession != null) {
                    providerSessions.add(providerSession);
                }
            }
        });
        return providerSessions;
    }

    final class CredentialManagerServiceStub extends ICredentialManager.Stub {
        @Override
        public ICancellationSignal executeGetCredential(
                GetCredentialRequest request,
                IGetCredentialCallback callback,
                final String callingPackage) {
            Log.i(TAG, "starting executeGetCredential with callingPackage: " + callingPackage);
            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final GetRequestSession session = new GetRequestSession(getContext(),
                    UserHandle.getCallingUserId(),
                    callback,
                    request,
                    callingPackage);

            // Initiate all provider sessions
            List<ProviderSession> providerSessions =
                    initiateProviderSessions(session, request.getGetCredentialOptions()
                            .stream().map(GetCredentialOption::getType)
                            .collect(Collectors.toList()));
            // TODO : Return error when no providers available

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(providerGetSession -> {
                providerGetSession.getRemoteCredentialService().onGetCredentials(
                        (GetCredentialsRequest) providerGetSession.getProviderRequest(),
                        /*callback=*/providerGetSession);
            });
            return cancelTransport;
        }

        @Override
        public ICancellationSignal executeCreateCredential(
                CreateCredentialRequest request,
                ICreateCredentialCallback callback,
                String callingPackage) {
            Log.i(TAG, "starting executeCreateCredential with callingPackage: " + callingPackage);
            // TODO : Implement cancellation
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();

            // New request session, scoped for this request only.
            final CreateRequestSession session = new CreateRequestSession(getContext(),
                    UserHandle.getCallingUserId(),
                    request,
                    callback,
                    callingPackage);

            // Initiate all provider sessions
            List<ProviderSession> providerSessions =
                    initiateProviderSessions(session, List.of(request.getType()));
            // TODO : Return error when no providers available

            // Iterate over all provider sessions and invoke the request
            providerSessions.forEach(providerCreateSession -> {
                providerCreateSession.getRemoteCredentialService().onCreateCredential(
                        (android.service.credentials.CreateCredentialRequest)
                                providerCreateSession.getProviderRequest(),
                        /*callback=*/providerCreateSession);
            });
            return cancelTransport;
        }

        @Override
        public ICancellationSignal clearCredentialSession(
                IClearCredentialSessionCallback callback, String callingPackage) {
            // TODO: implement.
            Log.i(TAG, "clearCredentialSession");
            ICancellationSignal cancelTransport = CancellationSignal.createTransport();
            return cancelTransport;
        }
    }
}

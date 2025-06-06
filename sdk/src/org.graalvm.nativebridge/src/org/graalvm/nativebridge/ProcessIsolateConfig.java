/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.nativebridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A configuration used by the {@link ProcessIsolate} and classes generated by the native bridge
 * processor.
 *
 * @see GenerateProcessToProcessBridge
 */
public final class ProcessIsolateConfig {

    private final Path isolateLauncher;
    private final List<String> launcherArguments;
    private final Path initiatorAddress;
    private final Supplier<ThreadLocal<ProcessIsolateThread>> threadLocalFactory;
    private final Consumer<? super ProcessIsolate> onIsolateTearDown;

    private ProcessIsolateConfig(Path isolateLauncher,
                    List<String> launcherArguments,
                    Path initiatorAddress,
                    Supplier<ThreadLocal<ProcessIsolateThread>> threadLocalFactory,
                    Consumer<? super ProcessIsolate> onIsolateTearDown) {
        this.isolateLauncher = isolateLauncher;
        this.launcherArguments = launcherArguments;
        this.initiatorAddress = initiatorAddress;
        this.threadLocalFactory = threadLocalFactory;
        this.onIsolateTearDown = onIsolateTearDown;
    }

    /**
     * Returns a path to the isolate launcher executable used to spawn the isolate.
     */
    Path getLauncher() {
        return isolateLauncher;
    }

    /**
     * Returns isolate launcher command line arguments.
     */
    List<String> getLauncherArguments() {
        return launcherArguments;
    }

    Path getInitiatorAddress() {
        return initiatorAddress;
    }

    Supplier<ThreadLocal<ProcessIsolateThread>> getThreadLocalFactory() {
        return threadLocalFactory;
    }

    Consumer<? super ProcessIsolate> getOnIsolateTearDownHook() {
        return onIsolateTearDown;
    }

    /**
     * Creates a new {@link Builder} for configuring a {@link ProcessIsolate} as an initiator, the
     * client process that spawns a subprocess isolate.
     *
     * @param isolateLauncher the path to the process isolate launcher binary.
     * @param initiatorSocketAddress the UNIX domain socket address, represented as a file path. The
     *            initiator process opens a socket at this address, which the target subprocess
     *            connects to in order to perform the initial handshake.
     *            <p>
     *            <strong>Note:</strong> This socket address must also be passed as a launcher
     *            argument using {@link ProcessIsolateConfig.Builder#launcherArgument(String)} so
     *            that the subprocess can locate it.
     *
     * @return a {@link Builder} configured for the initiator role.
     *
     * @see #createDefaultInitiatorSocketAddress(Path)
     */
    public static Builder newInitiatorBuilder(Path isolateLauncher, Path initiatorSocketAddress) {
        Objects.requireNonNull(isolateLauncher, "IsolateLauncher must be non-null.");
        Objects.requireNonNull(initiatorSocketAddress, "InitiatorSocketAddress must be non-null.");
        return new Builder(isolateLauncher, initiatorSocketAddress);
    }

    /**
     * Creates a new {@link Builder} for configuring a {@link ProcessIsolate} as a target, the
     * subprocess isolate spawned by an initiator.
     *
     * @param initiatorSocketAddress the UNIX domain socket address (represented as a file path) of
     *            the initiator process. The target subprocess will use this address to establish an
     *            initial handshake with the initiator.
     *
     * @return a {@link Builder} configured for the target role.
     */
    public static Builder newTargetBuilder(Path initiatorSocketAddress) {
        Objects.requireNonNull(initiatorSocketAddress, "InitiatorSocketAddress must be non-null.");
        return new Builder(null, initiatorSocketAddress);
    }

    /**
     * Generates a default UNIX domain socket address within the specified {@code folder}, which can
     * be used for listening to a polyglot isolate subprocess.
     *
     * @param folder the target directory in which to create the socket address, or {@code null} to
     *            use the current working directory.
     */
    public static Path createDefaultInitiatorSocketAddress(Path folder) {
        return ProcessIsolate.createUniqueSocketAddress(folder, "host");
    }

    /**
     * A builder class to construct {@link ProcessIsolateConfig} instances.
     */
    public static final class Builder {

        private final Path isolateLauncher;
        private final List<String> launcherArguments = new ArrayList<>();
        private final Path socketAddress;
        private Supplier<ThreadLocal<ProcessIsolateThread>> threadLocalFactory = ThreadLocal::new;
        private Consumer<? super ProcessIsolate> isolateTearDownHandler;

        private Builder(Path isolateLauncher, Path initiatorAddress) {
            this.isolateLauncher = isolateLauncher;
            this.socketAddress = initiatorAddress;
        }

        /**
         * Appends the specified {@code argument} to the list of command-line arguments for the
         * isolate launcher.
         */
        public Builder launcherArgument(String argument) {
            requireInitiatorBuilder();
            Objects.requireNonNull(argument, "Argument must be non-null.");
            launcherArguments.add(argument);
            return this;
        }

        /**
         * Appends the specified list of {@code arguments} to the isolate launcher's command-line
         * arguments.
         */
        public Builder launcherArguments(List<String> arguments) {
            requireInitiatorBuilder();
            Objects.requireNonNull(arguments, "Arguments must be non-null.");
            arguments.stream().map(Objects::requireNonNull).forEach(launcherArguments::add);
            return this;
        }

        /**
         * Registers a thread local factory whenever the default thread local handling should be
         * overridden. This can be useful to install a terminating thread local using JVMCI services
         * when needed.
         *
         * @see NativePeer
         */
        public Builder threadLocalFactory(Supplier<ThreadLocal<ProcessIsolateThread>> factory) {
            Objects.requireNonNull(factory, "Action must be non null.");
            this.threadLocalFactory = factory;
            return this;
        }

        /**
         * Registers a hook called before the process isolate is closed.
         */
        public Builder onIsolateTearDown(Consumer<? super ProcessIsolate> handler) {
            Objects.requireNonNull(handler, "Handler must be non-null");
            this.isolateTearDownHandler = handler;
            return this;
        }

        /**
         * Builds the {@link ProcessIsolateConfig}.
         *
         * @throws IllegalStateException when
         */
        public ProcessIsolateConfig build() {
            return new ProcessIsolateConfig(isolateLauncher, launcherArguments, socketAddress,
                            threadLocalFactory, isolateTearDownHandler);
        }

        private void requireInitiatorBuilder() {
            if (isolateLauncher == null) {
                throw new IllegalStateException("Supported only by Builder created by ProcessIsolateConfig.newInitiatorBuilder");
            }
        }
    }
}

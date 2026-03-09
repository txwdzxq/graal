local utils = import '../../../ci/ci_common/common-utils.libsonnet';
local vm = import 'vm.jsonnet';
local vm_common = import '../ci_common/common.jsonnet';
local graal_common = import '../../../ci/ci_common/common.jsonnet';

{
  local truffle_native_tck = graal_common.deps.svm + {
    run+: [
      ['mx', '--env', 'ce', '--dynamicimports', '/tools', '--native-images=lib:jvmcicompiler', 'gate', '--tags', 'build,truffle-native-tck,truffle-native-tck-sl'],
    ],
    notify_groups: ["truffle"],
    components+: ["truffletck"],
    timelimit: '35:00',
    name: 'gate-vm-truffle-native-tck-labs' + self.jdk_name + '-linux-amd64',
    logs+: [
      "*/call_tree.txt.gz"
    ]
  },

  local truffle_native_tck_wasm = graal_common.deps.svm + {
    run+: [
      ['mx', '--env', 'ce', '--dynamicimports', '/wasm', '--native-images=lib:jvmcicompiler', 'gate', '--tags', 'build,truffle-native-tck-wasm'],
    ],
    notify_groups: ["wasm"],
    components+: ["truffletck"],
    timelimit: '35:00',
    name: 'gate-vm-truffle-native-tck-wasm-labs' + self.jdk_name + '-linux-amd64',
  },

  local truffle_maven_downloader = graal_common.deps.svm + graal_common.deps.sulong + {
    run+: [
      ['mx', '--env', 'ce-llvm', '--native-images=', 'gate', '--no-warning-as-error', '--tags', 'build,maven-downloader'],
    ],
    notify_groups: ["truffle"],
    components+: ["truffle"],
    timelimit: '30:00',
    packages+: {
      maven: '==3.5.3',
    },
    name: 'gate-vm-ce-truffle-maven-downloader-labs' + self.jdk_name + '-linux-amd64',
  },

  # Truffle Isolate Unittest Jobs
  truffleisolate_gate(mode, time_limit): graal_common.deps.svm + {
    run: [
      ['mx', '--env', 'ce', '--components=env.COMPONENTS,nju', '--native-images=', 'gate', '--no-warning-as-error', '--tags', 'build,truffle_isolate_' + mode + '_unittest'],
    ],
    components+: ["truffle"],
    notify_groups: ["truffle"],
    timelimit: time_limit,
  },

  local truffle_isolate_modes = ['internal', 'external'],
  local truffle_isolate_platforms = [
    { os: 'linux', arch: 'amd64', build_type: 'full_vm_build', build_version: false },
    { os: 'darwin', arch: 'aarch64', build_type: 'full_vm_build', build_version: false },
    { os: 'windows', arch: 'amd64', build_type: 'svm_common', build_version: true }
  ],
  local truffle_isolate_unittest_jobs = [
    (
      local explicit_target = if (platform.os == 'windows' || platform.os == 'darwin') then 'daily' else '';
      local explicit_capabilities = if platform.os == 'windows' && mode == 'external' then { capabilities: ['windows_11'] } else {};
      local timelimit =
        # Darwin builders are highly affected by system load; gate times vary from ~13 minutes up to ~124 minutes.
        if platform.os == 'darwin' then  '2:15:00'
        else if platform.os == 'windows' then '1:30:00'
        else '1:00:00';
      vm.vm_java_Latest + vm_common.vm_base(platform.os, platform.arch, 'daily')  + self.truffleisolate_gate(mode, timelimit) + explicit_capabilities + {
        name: 'daily-vm-truffleisolate-' + mode + '-' + self.jdk_name + '-' + platform.os + '-' + platform.arch,
      }
    )
    for mode in truffle_isolate_modes
    for platform in truffle_isolate_platforms
  ],

  local builds = [
    vm.vm_java_Latest + graal_common.deps.svm + graal_common.deps.sulong + graal_common.deps.graalpy + vm.custom_vm + vm_common.vm_base('linux', 'amd64', 'tier3') + {
     run+: [
       ['mx', '--env', vm.edition, '--native-images=true', '--dy', 'graalpython', 'gate', '-B--targets=GRAALPY_NATIVE_STANDALONE', '--no-warning-as-error', '--tags', 'build,python'],
     ],
     notify_groups: ["python"],
     timelimit: '45:00',
     name: 'gate-vm-native-graalpython-linux-amd64',
    },
    vm.vm_java_Latest + vm_common.vm_base('linux', 'amd64', 'tier3')  + truffle_native_tck,
    vm.vm_java_Latest + vm_common.vm_base('linux', 'amd64', 'tier3')  + truffle_native_tck_wasm,
    vm.vm_java_Latest + vm_common.vm_base('linux', 'amd64', 'tier3')  + truffle_maven_downloader,
  ] + truffle_isolate_unittest_jobs,

  builds: utils.add_defined_in(builds, std.thisFile),
}

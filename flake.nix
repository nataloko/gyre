{
  description = "Paperouette Android 17 development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config.allowUnfree = true;
            config.android_sdk.accept_license = true;
          };
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37" ];
            buildToolsVersions = [ "37.0.0" ];
            includeEmulator = false;
            includeSystemImages = false;
          };
          sdk = android.androidsdk;
        in {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk17
              sdk
              pkgs.uv
            ];
            ANDROID_HOME = "${sdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
            JAVA_HOME = "${pkgs.jdk17}";
            shellHook = ''
              export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/37.0.0/aapt2"
            '';
          };
        });
    };
}

import { defineConfig } from "mygo-cli";
import pkg from "./package.json" with { type: "json" };

export default defineConfig({
  name: "Hark",
  slug: "hark",
  identifier: "io.github.riggedlab.hark.desktop",
  version: pkg.version,
  copyright: "© 2026 OWRIG",
  license: "MIT",
  main: ".",
  devUrl: "http://localhost:5173",
  devCommand: "bun run dev:web",
  buildCommand: "bun run build:web",
  frontendDist: "dist",
  bindings: "src/mygo.ts",
  out: "build",
  updates: {
    publicKey: "AwQdrrkU9eg+bYFxwAk+NzbXmnrv/4iCJolX22VIXHo=",
    github: "riggedlab/hark",
  },
  macos: process.env.MACOS_SIGNING_IDENTITY
    ? {
        signingIdentity: process.env.MACOS_SIGNING_IDENTITY,
        notarize: process.env.NOTARY_KEYCHAIN
          ? { keychainProfile: "notary", keychain: process.env.NOTARY_KEYCHAIN }
          : undefined,
      }
    : undefined,
  windows: process.env.WINDOWS_CERTIFICATE_FILE
    ? { certificate: process.env.WINDOWS_CERTIFICATE_FILE }
    : undefined,
});

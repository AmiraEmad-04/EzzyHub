import { defineConfig } from "vite";
import viteReact from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import tsConfigPaths from "vite-tsconfig-paths";
import { tanstackStart } from "@tanstack/react-start/plugin/vite";

// This project originally used @lovable.dev/vite-tanstack-config, a private
// package only available inside Lovable's build environment. That wrapper
// bundled: tanstackStart, viteReact, tailwindcss, tsConfigPaths, an asset
// pipeline (the *.asset.json imports), Lovable's dev-only component tagger,
// and error-reporting hooks. Since we're running outside Lovable now, this
// file reconstructs the pieces the app actually needs and drops the
// Lovable-only bits (component tagger, asset CDN pipeline, sandbox
// detection). Image/video imports were switched back to plain Vite asset
// imports in the route files that used them.
export default defineConfig({
  plugins: [
    tsConfigPaths({ projects: ["./tsconfig.json"] }),
    tailwindcss(),
    tanstackStart({
      // Keep using src/server.ts (the SSR error wrapper) as the server entry.
      server: { entry: "server" },
    }),
    viteReact(),
  ],
  server: {
    port: 3000,
  },
});

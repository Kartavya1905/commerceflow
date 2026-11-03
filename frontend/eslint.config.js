import js from "@eslint/js";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules", "src/api/schema", "playwright-report", "test-results"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: { ecmaVersion: 2022, globals: { ...globals.browser, ...globals.node } },
    plugins: { "react-hooks": reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // Tokens live in memory only (ROADMAP M8 gate): no web storage anywhere in the app.
      "no-restricted-globals": [
        "error",
        { name: "localStorage", message: "Tokens and session data stay in memory." },
        { name: "sessionStorage", message: "Tokens and session data stay in memory." },
      ],
      "no-restricted-properties": [
        "error",
        { object: "window", property: "localStorage", message: "Tokens stay in memory." },
        { object: "window", property: "sessionStorage", message: "Tokens stay in memory." },
      ],
    },
  },
  {
    // Tests may look at web storage, to prove the app never writes to it.
    files: ["**/*.test.{ts,tsx}", "e2e/**/*.ts"],
    rules: { "no-restricted-properties": "off", "no-restricted-globals": "off" },
  },
);

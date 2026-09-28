import { defineConfig } from '@hey-api/openapi-ts';

// Isolated from web/'s own TypeScript 7 pin: @hey-api/openapi-ts doesn't yet
// support TS7's new compiler internals (peer range is 5.5.3-6.x), so this
// generator lives in its own npm project with typescript@6.0.3.
export default defineConfig({
  input: '../../../api/build/openapi.json',
  output: '../../src/api-client',
});

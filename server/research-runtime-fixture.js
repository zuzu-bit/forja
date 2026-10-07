// Local test worker only; production entrypoint remains worker.js.
export { ResearchRegistry } from "./research-registry.js";
export { ResearchDevice } from "./research-device.js";
export default { fetch() { return new Response("research runtime fixture"); } };

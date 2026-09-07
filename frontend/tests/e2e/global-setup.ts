import { cleanCache } from "./coverage";

export default async function globalSetup() {
  await cleanCache();
}

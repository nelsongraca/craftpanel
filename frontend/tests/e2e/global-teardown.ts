import { generateReport } from "./coverage";

export default async function globalTeardown() {
  await generateReport();
}

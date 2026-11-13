import { ErrorScreen } from "@/components/errors/ErrorScreen";

export default function ServerErrorPage() {
  return <ErrorScreen kind="server" showRetry />;
}

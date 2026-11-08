import type { Metadata } from "next";
import { SignInFlow } from "@/components/signin-flow";

export const metadata: Metadata = {
  title: "Organizer sign in",
  robots: { index: false, follow: false },
};

export default function SignInPage() {
  return <SignInFlow />;
}

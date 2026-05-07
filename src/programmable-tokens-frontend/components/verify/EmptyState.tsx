"use client";

import { Card } from "@/components/ui/card";
import { Inbox } from "lucide-react";

export function EmptyState({ message }: { message: string }) {
  return (
    <Card className="p-10 flex flex-col items-center text-center gap-3">
      <Inbox className="h-10 w-10 text-dark-500" />
      <p className="text-sm text-dark-300">{message}</p>
    </Card>
  );
}

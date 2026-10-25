'use client';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useQuestions } from '@/lib/api/queries';
import { QuestionList } from '@/components/questions/question-list';
import { ApiErrorAlert } from '@/components/layout/api-error-alert';
import type { QuestionsListResponse } from '@/types/homer';

function QuestionsContent({ data, error }: { data?: QuestionsListResponse; error: Error | null }) {
  // A failed first load is not an empty inbox: say why there is nothing to show.
  if (!data && error) return <ApiErrorAlert title="Failed to load questions" error={error} />;
  const questions = data?.questions ?? [];
  if (questions.length === 0) return <p className="text-sm text-muted-foreground">No open questions.</p>;
  return <QuestionList questions={questions} total={data?.total ?? questions.length} />;
}

export default function QuestionsPage() {
  const { data, error } = useQuestions({ status: 'OPEN' });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Questions</CardTitle>
        <CardDescription>Open questions awaiting your answer</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <QuestionsContent data={data} error={error} />
      </CardContent>
    </Card>
  );
}

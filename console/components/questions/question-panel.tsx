'use client';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useQuestions } from '@/lib/api/queries';
import { QuestionList } from './question-list';

export function QuestionPanel({ processId }: { processId: number }) {
  const { data } = useQuestions({ processId });
  const questions = data?.questions ?? [];

  if (questions.length === 0) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Questions</CardTitle>
        <CardDescription>The agent asked for operator input</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <QuestionList questions={questions} total={data?.total ?? questions.length} />
      </CardContent>
    </Card>
  );
}

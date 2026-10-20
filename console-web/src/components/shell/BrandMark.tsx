export function BrandMark({ className = "" }: { className?: string }) {
  return <img src="/coat-of-arms.png" alt="Government of Rwanda coat of arms" className={`object-contain ${className}`.trim()} />;
}

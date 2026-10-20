/**
 * TanStack Table, rendered with the shadcn table primitives. Client-side
 * sorting and an optional global filter; server-paged lists pass `manual`.
 */
import { useState, type ReactNode } from "react";
import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type SortingState,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp } from "lucide-react";

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

export function DataTable<T>({
  columns,
  data,
  globalFilter,
  empty,
  getRowId,
  onRowClick,
  className,
}: {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  columns: ColumnDef<T, any>[];
  data: T[];
  globalFilter?: string;
  empty?: ReactNode;
  getRowId?: (row: T, index: number) => string;
  onRowClick?: (row: T) => void;
  className?: string;
}) {
  const [sorting, setSorting] = useState<SortingState>([]);
  const table = useReactTable({
    data,
    columns,
    state: { sorting, globalFilter },
    onSortingChange: setSorting,
    getRowId,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
  });

  const rows = table.getRowModel().rows;
  return (
    <div className={cn("rounded-lg border", className)}>
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((hg) => (
            <TableRow key={hg.id}>
              {hg.headers.map((h) => {
                const sortable = h.column.getCanSort();
                const dir = h.column.getIsSorted();
                return (
                  <TableHead key={h.id}>
                    {h.isPlaceholder ? null : sortable ? (
                      <button type="button" className="inline-flex items-center gap-1 uppercase" onClick={h.column.getToggleSortingHandler()}>
                        {flexRender(h.column.columnDef.header, h.getContext())}
                        {dir === "asc" ? <ArrowUp className="h-3 w-3" /> : dir === "desc" ? <ArrowDown className="h-3 w-3" /> : null}
                      </button>
                    ) : (
                      flexRender(h.column.columnDef.header, h.getContext())
                    )}
                  </TableHead>
                );
              })}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {rows.length === 0 ? (
            <TableRow>
              <TableCell colSpan={columns.length} className="py-8 text-center text-sm text-muted-foreground">
                {empty ?? "—"}
              </TableCell>
            </TableRow>
          ) : (
            rows.map((row) => (
              <TableRow
                key={row.id}
                className={onRowClick ? "cursor-pointer" : undefined}
                onClick={onRowClick ? () => onRowClick(row.original) : undefined}
              >
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                ))}
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </div>
  );
}

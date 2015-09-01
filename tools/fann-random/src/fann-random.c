#include <stdio.h>
#include <stdlib.h>
#include <getopt.h>

#include "fann.h"

static char * usage_message =
  "fann-random -l[1st hidden size] -l[2nd hidden size] -l... [options] file\n"
  "Generate a random fann network with a specific topology and write it\n"
  "to the specified file. The network must have at least two layers.\n"
  "\n"
  "Options:\n"
  "  -f, --fixed-point          write output file as fixed point\n"
  "  -h, --help                 print this help and exit\n"
  "  -l, --layers               adds a layer to the network of a specific size\n"
  ;

void usage () {
  printf("Usage: %s", usage_message);
}

typedef struct {
  int size;
  int valid;
  unsigned int * layer_array;
} layers_struct;

void add_layer (layers_struct ** layers, int new_layer) {
  static int realloc_size = 4;

  // Reallocate the layer array if needed
  if ((*layers)->valid == (*layers)->size) {
    (*layers)->layer_array =
    (unsigned int *) realloc ((*layers)->layer_array,
                              ((*layers)->size + realloc_size) * sizeof(int));
    (*layers)->size += realloc_size;
  }

  (*layers)->layer_array[(*layers)->valid] = new_layer;
  (*layers)->valid++;
}

int main (int argc, char * argv[]) {
  int c, fixed_point = 0;
  layers_struct * layers;
  const char * file;

  layers = (layers_struct *) malloc (4 * sizeof(layers_struct));

  layers->size = 4;
  layers->valid = 0;
  layers->layer_array = (unsigned int *) malloc (layers->size * sizeof(int));

  while (1) {
    static struct option long_options[] = {
      {"fixed-point", no_argument,       0, 'f'},
      {"help",        no_argument,       0, 'h'},
      {"add-layer",   required_argument, 0, 'l'}
    };
    int option_index = 0;
    c = getopt_long (argc, argv, "fhl:", long_options, &option_index);
    if (c == -1)
      break;
    switch (c) {
    case 'f':
      fixed_point = 1;
      break;
    case 'h':
      usage();
      goto failure;
    case 'l':
      add_layer(&layers, atoi(optarg));
      break;
    default:
      abort ();
    }
  }

  if (optind != argc - 1) {
    fprintf(stderr, "[ERROR] Missing output file\n");
    usage();
    goto failure;
  }
  file = argv[optind];

  if (layers->valid < 2) {
    fprintf(stderr, "[ERROR] Network needs at least two layers\n");
    usage();
    goto failure;
  }

  // Create the network
  struct fann * ann;
  ann = fann_create_standard_array(layers->valid, layers->layer_array);
  if (fixed_point) {
    fann_save_to_fixed(ann, file);
  }
  else {
    fann_save(ann, file);
  }
  fann_destroy(ann);

  // Destroy the layers array
 failure:
  free(layers->layer_array);
  free(layers);

  return 0;
}